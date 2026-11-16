package app.depot.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** What the watch tells the phone about one repository's app. */
data class WatchApp(
    val key: String,
    val status: Status = Status.Unknown,
    val installedVersion: String? = null,
    val latestTag: String? = null,
    val busy: Busy? = null,
    val error: String? = null,
    /** Android is waiting for a tap on the watch before it installs. */
    val needsUser: Boolean = false,
)

/** Everything the watch reports. [ack] is the id of the last command it took on. */
data class WatchState(val apps: List<WatchApp>, val ack: Long = 0) {
    fun app(key: String): WatchApp? = apps.firstOrNull { it.key == key }
}

/** What the phone can ask the watch to do. [id] comes back in [WatchState.ack]. */
sealed interface WatchCommand {
    val id: Long

    data class Install(val key: String, override val id: Long = 0) : WatchCommand
    data class Check(override val id: Long = 0) : WatchCommand
}

/**
 * The wire format between the phone and watch apps. The two can be on different versions for a
 * while, so decoding ignores fields it does not know and fills in the ones that are missing.
 */
object WatchLink {
    /** Data item written by the phone: token and repository list. */
    const val CONFIG_PATH = "/depot/config"

    /** Data item written by the watch: a [WatchState]. */
    const val STATE_PATH = "/depot/watch-state"

    /** Message sent by the phone: a [WatchCommand]. */
    const val COMMAND_PATH = "/depot/command"

    /** Declared in the watch app's wear.xml, so the phone only messages nodes that have it. */
    const val CAPABILITY = "depot_watch"

    private const val VERSION = 1
    private const val MAX_ERROR = 500

    private val STATUSES = listOf(
        Status.Unknown, Status.NotInstalled, Status.UpToDate, Status.UpdateAvailable,
        Status.NoRelease, Status.NoAsset, Status.OtherDevice,
    )

    private fun name(status: Status) = when (status) {
        Status.Unknown -> "unknown"
        Status.NotInstalled -> "not_installed"
        Status.UpToDate -> "up_to_date"
        Status.UpdateAvailable -> "update_available"
        Status.NoRelease -> "no_release"
        Status.NoAsset -> "no_asset"
        Status.OtherDevice -> "other_device"
    }

    fun stateOf(apps: List<AppState>, ack: Long = 0) = WatchState(
        apps = apps.map {
            WatchApp(
                key = it.repo.key,
                status = it.status,
                installedVersion = it.installedVersion,
                latestTag = it.release?.tag,
                // Whole percent is all that is sent, so keep no more than that here either.
                busy = (it.busy as? Busy.Downloading)?.let { d -> Busy.Downloading(percent(d) / 100f) } ?: it.busy,
                error = it.error?.take(MAX_ERROR),
                needsUser = it.needsUser,
            )
        },
        ack = ack,
    )

    fun percent(busy: Busy.Downloading): Int = (busy.fraction * 100).roundToInt().coerceIn(0, 100)

    fun encodeState(state: WatchState): String {
        val apps = JSONArray()
        state.apps.forEach { app ->
            val o = JSONObject().put("key", app.key).put("status", name(app.status))
            // Absent rather than null: Android's org.json reads a JSON null back as the text "null".
            app.installedVersion?.let { o.put("installed", it) }
            app.latestTag?.let { o.put("latest", it) }
            when (val busy = app.busy) {
                Busy.Checking -> o.put("busy", "checking")
                is Busy.Downloading -> o.put("busy", "downloading").put("percent", percent(busy))
                Busy.Installing -> o.put("busy", "installing")
                null -> {}
            }
            app.error?.let { o.put("error", it.take(MAX_ERROR)) }
            if (app.needsUser) o.put("confirm", true)
            apps.put(o)
        }
        return JSONObject().put("v", VERSION).put("ack", state.ack).put("apps", apps).toString()
    }

    /** Returns null only when [json] is not a JSON object at all. */
    fun decodeState(json: String?): WatchState? {
        val root = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return null
        val arr = root.optJSONArray("apps") ?: JSONArray()
        val apps = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.text("key") ?: return@mapNotNull null
            val status = o.text("status")
            WatchApp(
                key = key,
                status = STATUSES.firstOrNull { name(it) == status } ?: Status.Unknown,
                installedVersion = o.text("installed"),
                latestTag = o.text("latest"),
                busy = when (o.text("busy")) {
                    "checking" -> Busy.Checking
                    "downloading" -> Busy.Downloading(o.optInt("percent").coerceIn(0, 100) / 100f)
                    "installing" -> Busy.Installing
                    else -> null
                },
                error = o.text("error"),
                needsUser = o.optBoolean("confirm"),
            )
        }
        return WatchState(apps, root.optLong("ack"))
    }

    fun encodeCommand(command: WatchCommand): ByteArray {
        val o = JSONObject().put("v", VERSION).put("id", command.id)
        when (command) {
            is WatchCommand.Install -> o.put("type", "install").put("key", command.key)
            is WatchCommand.Check -> o.put("type", "check")
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns null for anything this version cannot act on, such as a command added later. */
    fun decodeCommand(bytes: ByteArray?): WatchCommand? {
        val o = runCatching { JSONObject(String(bytes ?: return null, Charsets.UTF_8)) }.getOrNull() ?: return null
        val id = o.optLong("id")
        return when (o.text("type")) {
            "install" -> o.text("key")?.let { WatchCommand.Install(it, id) }
            "check" -> WatchCommand.Check(id)
            else -> null
        }
    }

    /**
     * True when [new] differs from [old] only in how far downloads have got. The watch waits
     * before writing such a change, so a download does not flood the data layer.
     */
    fun onlyProgressChanged(old: WatchState, new: WatchState): Boolean {
        fun WatchState.flat() = copy(
            apps = apps.map { if (it.busy is Busy.Downloading) it.copy(busy = Busy.Downloading(0f)) else it },
        )
        return old != new && old.flat() == new.flat()
    }

    /**
     * Whether the phone should show a watch line for [repo]. The watch's own report wins; until it
     * arrives, the phone goes by the setting and by the file names in the release it knows.
     */
    fun hasWatchApp(repo: RepoEntry, phoneRelease: Release?, watch: WatchApp?): Boolean = when {
        repo.target == Target.PHONE -> false
        repo.target == Target.WATCH -> true
        watch != null && (watch.busy is Busy.Downloading || watch.busy == Busy.Installing || watch.needsUser) -> true
        watch?.status == Status.NotInstalled || watch?.status == Status.UpdateAvailable ||
            watch?.status == Status.UpToDate -> true
        watch?.status == Status.NoAsset -> false
        else -> phoneRelease?.assets.orEmpty().any {
            it.name.endsWith(".apk", ignoreCase = true) && AssetPicker.isWearName(it.name)
        }
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
}
