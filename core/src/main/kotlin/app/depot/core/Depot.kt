package app.depot.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The store itself: the list of apps, what state each is in, and the actions on them. */
class Depot private constructor(private val app: Application, val variant: Variant) {
    val settings = Settings(app)
    val github = GitHub { settings.token }

    private val _apps = MutableStateFlow<List<AppState>>(emptyList())
    val apps: StateFlow<List<AppState>> = _apps

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Bumped whenever the token or repository list changes, so screens can re-read settings. */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion

    private val busy = ConcurrentHashMap<String, Busy>()
    private val errors = ConcurrentHashMap<String, String>()
    private val checked = ConcurrentHashMap.newKeySet<String>()
    private val needsUser = ConcurrentHashMap.newKeySet<String>()
    private val installLock = Mutex()
    private val refreshLock = Mutex()

    /** Called after the token or repository list changes. The phone uses it to update the watch. */
    var onConfigChanged: (() -> Unit)? = null

    @Volatile
    var foreground = false
        private set

    init {
        var started = 0
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++; foreground = true }
            override fun onActivityStopped(activity: Activity) { started--; foreground = started > 0 }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        publish()
        UpdateWorker.schedule(app, settings.autoCheck)
    }

    val hasToken: Boolean get() = !settings.token.isNullOrBlank()

    // ---- configuration ----

    fun setToken(token: String?) {
        settings.token = token
        configChanged()
    }

    fun addRepo(repo: RepoEntry): Boolean {
        val current = settings.repos
        if (current.any { it.key.equals(repo.key, ignoreCase = true) }) return false
        settings.repos = current + repo
        configChanged()
        return true
    }

    fun updateRepo(repo: RepoEntry) {
        settings.repos = settings.repos.map { if (it.key == repo.key) repo else it }
        // A different target or channel can mean a different APK, so check again.
        checked.remove(repo.key)
        errors.remove(repo.key)
        configChanged()
    }

    fun removeRepo(key: String) {
        settings.repos = settings.repos.filterNot { it.key == key }
        settings.forget(key)
        needsUser.remove(key)
        errors.remove(key)
        checked.remove(key)
        configChanged()
    }

    fun setAutoCheck(enabled: Boolean) {
        settings.autoCheck = enabled
        UpdateWorker.schedule(app, enabled)
    }

    fun setAutoInstall(enabled: Boolean) {
        settings.autoInstall = enabled
        configChanged()
    }

    /** Used on the watch: take over the token and repository list sent by the phone. */
    fun replaceConfig(token: String?, repos: List<RepoEntry>, autoInstall: Boolean) {
        val before = settings.repos
        val cleanToken = token?.trim()?.ifEmpty { null }
        if (before == repos && settings.token == cleanToken && settings.autoInstall == autoInstall) return
        settings.token = cleanToken
        settings.repos = repos
        settings.autoInstall = autoInstall
        (before.map { it.key } - repos.map { it.key }.toSet()).forEach { settings.forget(it) }
        repos.forEach { r -> if (before.none { it == r }) checked.remove(r.key) }
        _configVersion.value++
        publish()
    }

    private fun configChanged() {
        _configVersion.value++
        publish()
        onConfigChanged?.invoke()
    }

    // ---- state ----

    fun publish() {
        val abis = Build.SUPPORTED_ABIS.toList()
        _apps.value = settings.repos.map { repo ->
            val record = settings.record(repo.key)
            val release = settings.cachedRelease(repo.key)
            val asset = release?.let { AssetPicker.pick(it.assets, variant, repo.target, abis) }
            val installed = record.packageName?.let { packageInfo(it) }
            val excluded = (variant == Variant.PHONE && repo.target == Target.WATCH) ||
                (variant == Variant.WEAR && repo.target == Target.PHONE)
            val status = when {
                excluded -> Status.OtherDevice
                release == null -> if (repo.key in checked) Status.NoRelease else Status.Unknown
                asset == null -> if (installed != null) Status.UpToDate else Status.NoAsset
                installed == null -> Status.NotInstalled
                record.assetId == asset.id -> Status.UpToDate
                else -> Status.UpdateAvailable
            }
            AppState(
                repo = repo,
                label = record.label ?: repo.name,
                packageName = record.packageName,
                installedVersion = installed?.versionName,
                release = release,
                asset = asset,
                status = status,
                busy = busy[repo.key],
                error = errors[repo.key],
                needsUser = repo.key in needsUser,
            )
        }
    }

    private fun packageInfo(packageName: String): PackageInfo? =
        runCatching { app.packageManager.getPackageInfo(packageName, 0) }.getOrNull()

    internal fun recordInstalled(key: String, packageName: String?, label: String?, assetId: Long) {
        if (key.isEmpty()) return
        val old = settings.record(key)
        settings.setRecord(key, InstallRecord(packageName ?: old.packageName, label ?: old.label, assetId))
        needsUser.remove(key)
        errors.remove(key)
        publish()
    }

    internal fun reportError(key: String, message: String) {
        if (key.isEmpty()) return
        errors[key] = message
        needsUser.remove(key)
        publish()
    }

    /** Android is showing, or has a notification for, its confirmation of this app's install. */
    internal fun awaitingUser(key: String) {
        if (key.isEmpty()) return
        needsUser += key
        publish()
    }

    // ---- actions ----

    /** Asks GitHub for the newest release of every repository. Returns the apps that can be updated. */
    suspend fun refresh(): List<AppState> = refreshLock.withLock {
        if (!hasToken) return@withLock emptyList()
        _refreshing.value = true
        try {
            val repos = settings.repos.filterNot {
                (variant == Variant.PHONE && it.target == Target.WATCH) ||
                    (variant == Variant.WEAR && it.target == Target.PHONE)
            }
            repos.forEach { if (busy[it.key] == null) busy[it.key] = Busy.Checking }
            publish()
            coroutineScope {
                repos.map { repo ->
                    async(Dispatchers.IO) {
                        try {
                            val release = github.latestRelease(repo)
                            settings.setCachedRelease(repo.key, release)
                            checked += repo.key
                            errors.remove(repo.key)
                        } catch (e: Exception) {
                            errors[repo.key] = e.message ?: "Could not check for updates"
                        } finally {
                            busy.remove(repo.key, Busy.Checking)
                            publish()
                        }
                    }
                }.awaitAll()
            }
            settings.lastCheck = System.currentTimeMillis()
        } finally {
            _refreshing.value = false
            publish()
        }
        _apps.value.filter { it.status == Status.UpdateAvailable }
    }

    /** Downloads the app's APK and installs it. One at a time, so prompts never stack up. */
    suspend fun install(key: String): InstallResult {
        val queued = _apps.value.firstOrNull { it.repo.key == key }
            ?: return InstallResult.Failure("Unknown app")
        if (queued.asset == null) return InstallResult.Failure("No APK for this device in the release")
        errors.remove(key)
        needsUser.remove(key)
        busy[key] = Busy.Downloading(0f)
        publish()
        return installLock.withLock {
            val result = try {
                withContext(Dispatchers.IO) { downloadAndInstall(key) }
            } catch (e: Exception) {
                InstallResult.Failure(e.message ?: "Something went wrong")
            }
            busy.remove(key)
            if (result is InstallResult.Failure) errors[key] = result.message
            // NeedsUser leaves the question open: the answer arrives later through InstallReceiver.
            if (result !is InstallResult.NeedsUser) needsUser.remove(key)
            publish()
            result
        }
    }

    private suspend fun downloadAndInstall(key: String): InstallResult {
        // Re-read: the release may have changed while this install waited its turn.
        val state = _apps.value.firstOrNull { it.repo.key == key } ?: return InstallResult.Failure("Unknown app")
        val asset = state.asset ?: return InstallResult.Failure("No APK for this device in the release")
        val dir = File(app.cacheDir, "apks").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != "${asset.id}.apk") it.delete() }
        val apk = File(dir, "${asset.id}.apk")
        if (!apk.exists() || (asset.size > 0 && apk.length() != asset.size)) {
            apk.delete()
            var lastStep = -1
            github.download(state.repo, asset, apk) { fraction ->
                val step = (fraction * 50).toInt()
                if (step != lastStep) {
                    lastStep = step
                    busy[key] = Busy.Downloading(fraction)
                    publish()
                }
            }
        }

        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0)
        if (archive == null) {
            apk.delete()
            return InstallResult.Failure("${asset.name} is not a valid APK")
        }
        val label = archive.applicationInfo?.let {
            it.sourceDir = apk.path
            it.publicSourceDir = apk.path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        } ?: state.repo.name
        val old = settings.record(key)
        settings.setRecord(key, old.copy(packageName = archive.packageName, label = label))

        val installed = packageInfo(archive.packageName)
        if (installed != null) {
            val have = PackageInfoCompat.getLongVersionCode(installed)
            val offered = PackageInfoCompat.getLongVersionCode(archive)
            if (have == offered) {
                // Already on this build, for example installed by hand earlier.
                recordInstalled(key, archive.packageName, label, asset.id)
                apk.delete()
                return InstallResult.Success
            }
            if (have > offered) {
                apk.delete()
                return InstallResult.Failure(
                    "The installed build ($have) is newer than this release ($offered). " +
                        "Raise versionCode in the app before releasing."
                )
            }
        }

        busy[key] = Busy.Installing
        publish()
        val result = Installer.install(app, apk, archive.packageName, label, key, asset.id)
        if (result is InstallResult.Success) apk.delete()
        return result
    }

    fun canInstallPackages(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun launchIntent(packageName: String): Intent? = app.packageManager.getLaunchIntentForPackage(packageName)

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        @Volatile
        private var instance: Depot? = null

        /** Must be called from Application.onCreate so the store knows which device it is on. */
        fun init(app: Application, variant: Variant): Depot =
            instance ?: synchronized(this) { instance ?: Depot(app, variant).also { instance = it } }

        fun get(context: Context): Depot =
            instance ?: error("Depot.init was not called from ${context.applicationContext.javaClass.name}")
    }
}
