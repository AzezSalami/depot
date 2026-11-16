package app.depot.core

/** Which kind of device this copy of the store is running on. */
enum class Variant { PHONE, WEAR }

/** Where the user wants a repository's app to be installed. */
enum class Target { AUTO, PHONE, WATCH, BOTH }

data class RepoEntry(
    val owner: String,
    val name: String,
    val target: Target = Target.AUTO,
    val prerelease: Boolean = false,
) {
    val key: String get() = "$owner/$name"

    companion object {
        private val PART = Regex("[A-Za-z0-9._-]+")

        /** Accepts `owner/repo`, a github.com URL or a git remote. Returns null if it is none of those. */
        fun parse(input: String): RepoEntry? {
            var s = input.trim()
            s = s.removePrefix("git@github.com:")
            s = s.replace(Regex("^(https?://)?(www\\.)?github\\.com/", RegexOption.IGNORE_CASE), "")
            s = s.substringBefore('?').substringBefore('#').trim('/')
            val parts = s.split('/')
            if (parts.size < 2) return null
            val owner = parts[0]
            val name = parts[1].removeSuffix(".git")
            if (!PART.matches(owner) || !PART.matches(name)) return null
            return RepoEntry(owner, name)
        }
    }
}

data class Asset(val id: Long, val name: String, val size: Long)

data class Release(
    val tag: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val assets: List<Asset>,
)

/** What this device knows about a repository's app from earlier installs. */
data class InstallRecord(
    val packageName: String? = null,
    val label: String? = null,
    val assetId: Long = 0,
)

sealed interface Status {
    data object Unknown : Status
    data object NotInstalled : Status
    data object UpToDate : Status
    data object UpdateAvailable : Status
    data object NoRelease : Status
    data object NoAsset : Status
    data object OtherDevice : Status
}

sealed interface Busy {
    data object Checking : Busy
    data class Downloading(val fraction: Float) : Busy
    data object Installing : Busy
}

data class AppState(
    val repo: RepoEntry,
    val label: String,
    val packageName: String?,
    val installedVersion: String?,
    val release: Release?,
    val asset: Asset?,
    val status: Status,
    val busy: Busy? = null,
    val error: String? = null,
    /** Android asked the user to confirm the install and has not had an answer yet. */
    val needsUser: Boolean = false,
)
