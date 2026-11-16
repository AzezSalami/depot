package app.depot.store

import android.content.Context
import android.net.Uri
import app.depot.core.Depot
import app.depot.core.Settings
import app.depot.core.WatchCommand
import app.depot.core.WatchLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

private const val PATH = WatchLink.CONFIG_PATH

/**
 * Receives the token and repository list whenever the phone app changes them, and the phone's
 * commands. Android starts the process for either if it is not running; Application.onCreate has
 * finished by the time anything here is called.
 */
class ConfigService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val depot = Depot.get(this)
        for (event in events) {
            if (event.dataItem.uri.path != PATH) continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    apply(depot, event.dataItem)
                    // With no screen open nothing else looks at the new list, and the phone is waiting to show it.
                    (application as DepotWearApp).handle(WatchCommand.Check())
                }
                // The phone switched sharing off: forget the token here too.
                DataEvent.TYPE_DELETED -> depot.replaceConfig(null, emptyList(), false)
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchLink.COMMAND_PATH) return
        // Unknown commands from a newer phone app decode to null and are dropped.
        val command = WatchLink.decodeCommand(event.data) ?: return
        // Handed to the application: this service is gone as soon as the method returns.
        (application as DepotWearApp).handle(command)
    }
}

private fun apply(depot: Depot, item: DataItem) {
    val map = DataMapItem.fromDataItem(item).dataMap
    depot.replaceConfig(
        token = map.getString("token"),
        repos = Settings.reposFromJson(map.getString("repos") ?: "[]"),
        autoInstall = map.getBoolean("autoInstall", false),
    )
}

/** Reads what the phone last sent, for when the watch app was not running at the time. Blocks. */
fun pullConfig(context: Context): Boolean = try {
    val items = Tasks.await(
        Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*$PATH")), 10, TimeUnit.SECONDS
    )
    try {
        val item = items.firstOrNull()
        if (item != null) apply(Depot.get(context), item)
        item != null
    } finally {
        items.release()
    }
} catch (e: Exception) {
    false
}
