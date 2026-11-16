package app.depot.store

import android.content.Context
import android.net.Uri
import app.depot.core.Depot
import app.depot.core.Settings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

private const val PATH = "/depot/config"

/** Receives the token and repository list whenever the phone app changes them. */
class ConfigService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val depot = Depot.get(this)
        for (event in events) {
            if (event.dataItem.uri.path != PATH) continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> apply(depot, event.dataItem)
                // The phone switched sharing off: forget the token here too.
                DataEvent.TYPE_DELETED -> depot.replaceConfig(null, emptyList(), false)
            }
        }
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
