package app.depot.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Checks GitHub a few times a day and, if the user asked for it, installs what it finds. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val depot = Depot.get(applicationContext)
        if (!depot.hasToken) return Result.success()
        val updates = try {
            depot.refresh()
        } catch (e: Exception) {
            return Result.retry()
        }
        if (updates.isEmpty()) return Result.success()

        val waiting = updates.toMutableList()
        if (depot.settings.autoInstall) {
            val done = mutableListOf<String>()
            for (app in updates) {
                val result = withTimeoutOrNull(5 * 60_000L) { depot.install(app.repo.key) }
                when (result) {
                    InstallResult.Success -> { done += app.label; waiting -= app }
                    // The user already has a "confirm install" notification for this one.
                    InstallResult.NeedsUser -> waiting -= app
                    else -> {}
                }
            }
            Notifier.updated(applicationContext, done)
        }
        Notifier.updatesAvailable(applicationContext, waiting.map { it.label })
        return Result.success()
    }

    companion object {
        private const val NAME = "depot-update-check"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
