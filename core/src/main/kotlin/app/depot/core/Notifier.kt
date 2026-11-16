package app.depot.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "updates"
    private const val ID_UPDATES = 1
    private const val ID_CONFIRM_BASE = 1000

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        return true
    }

    @Suppress("MissingPermission")
    fun updatesAvailable(context: Context, labels: List<String>) {
        if (labels.isEmpty() || !allowed(context)) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val title = if (labels.size == 1) "Update available" else "${labels.size} updates available"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_depot_notify)
            .setContentTitle(title)
            .setContentText(labels.joinToString(", "))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPDATES, n)
    }

    @Suppress("MissingPermission")
    fun updated(context: Context, labels: List<String>) {
        if (labels.isEmpty() || !allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_depot_notify)
            .setContentTitle(if (labels.size == 1) "App updated" else "${labels.size} apps updated")
            .setContentText(labels.joinToString(", "))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPDATES + 1, n)
    }

    @Suppress("MissingPermission")
    fun confirmInstall(context: Context, sessionId: Int, label: String, confirm: Intent) {
        if (!allowed(context)) return
        val pi = PendingIntent.getActivity(
            context, sessionId, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_depot_notify)
            .setContentTitle("Confirm install of $label")
            .setContentText("Tap to finish installing.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_CONFIRM_BASE + sessionId, n)
    }

    fun cancelConfirm(context: Context, sessionId: Int) =
        NotificationManagerCompat.from(context).cancel(ID_CONFIRM_BASE + sessionId)

    fun clearUpdates(context: Context) = NotificationManagerCompat.from(context).cancel(ID_UPDATES)
}
