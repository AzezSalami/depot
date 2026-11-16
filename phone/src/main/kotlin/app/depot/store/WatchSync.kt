package app.depot.store

import android.content.Context
import android.net.Uri
import app.depot.core.Settings
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Hands the token and repository list to the watch over the Wear OS data layer, so nothing
 * has to be typed on the watch. All functions block; call them off the main thread.
 */
object WatchSync {
    const val PATH = "/depot/config"

    /** Returns null on success, otherwise a message for the user. */
    fun push(context: Context, settings: Settings): String? = try {
        val client = Wearable.getDataClient(context)
        if (settings.syncToWatch) {
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putString("token", settings.token.orEmpty())
                dataMap.putString("repos", Settings.reposToJson(settings.repos))
                dataMap.putBoolean("autoInstall", settings.autoInstall)
                dataMap.putLong("stamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Tasks.await(client.putDataItem(request), 15, TimeUnit.SECONDS)
        } else {
            Tasks.await(client.deleteDataItems(Uri.parse("wear://*$PATH")), 15, TimeUnit.SECONDS)
        }
        null
    } catch (e: Exception) {
        "Could not reach the watch: ${e.cause?.message ?: e.message ?: "unknown error"}"
    }

    /** Names of the watches connected right now. */
    fun watches(context: Context): List<String> = try {
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS).map { it.displayName }
    } catch (e: Exception) {
        emptyList()
    }
}
