package app.depot.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface InstallResult {
    data object Success : InstallResult
    /** Android wants the user to confirm, and the store is not on screen. A notification was posted. */
    data object NeedsUser : InstallResult
    data class Failure(val message: String) : InstallResult
}

object Installer {
    internal const val EXTRA_KEY = "depot.key"
    internal const val EXTRA_ASSET = "depot.asset"
    internal const val EXTRA_PACKAGE = "depot.package"
    internal const val EXTRA_LABEL = "depot.label"
    internal val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()

    suspend fun install(
        context: Context,
        apk: File,
        packageName: String,
        label: String,
        repoKey: String,
        assetId: Long,
    ): InstallResult {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Lets Android apply updates without a prompt once this store has installed the app.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return InstallResult.Failure(e.message ?: "Could not start the install")
        }
        val result = CompletableDeferred<InstallResult>()
        pending[sessionId] = result
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                    .putExtra(EXTRA_KEY, repoKey)
                    .putExtra(EXTRA_ASSET, assetId)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_LABEL, label)
                // Mutable because the system adds the install status to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            pending.remove(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            return InstallResult.Failure(e.message ?: "Could not write the install session")
        }
        return try {
            result.await()
        } finally {
            pending.remove(sessionId)
        }
    }
}

/** Receives the outcome of every install session, including ones that finish after the app was closed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val key = intent.getStringExtra(Installer.EXTRA_KEY).orEmpty()
        val label = intent.getStringExtra(Installer.EXTRA_LABEL) ?: key
        val waiter = Installer.pending[sessionId]
        val depot = Depot.get(context)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val started = depot.foreground && runCatching { context.startActivity(confirm) }.isSuccess
                if (!started) {
                    Notifier.confirmInstall(context, sessionId, label, confirm)
                    waiter?.complete(InstallResult.NeedsUser)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Written here as well so it survives the store updating itself or being killed.
                depot.recordInstalled(
                    key,
                    intent.getStringExtra(Installer.EXTRA_PACKAGE),
                    label,
                    intent.getLongExtra(Installer.EXTRA_ASSET, 0),
                )
                Notifier.cancelConfirm(context, sessionId)
                waiter?.complete(InstallResult.Success)
            }

            else -> {
                Notifier.cancelConfirm(context, sessionId)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "The device blocked this install."
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "Conflicts with the installed copy, usually a different signing key. Uninstall it first."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This APK is not compatible with the device."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or not signed."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed."
                }
                if (waiter != null) waiter.complete(InstallResult.Failure(message)) else depot.reportError(key, message)
            }
        }
    }
}
