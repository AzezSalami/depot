package app.depot.store

import android.content.Context
import android.net.Uri
import app.depot.core.WatchCommand
import app.depot.core.WatchLink
import app.depot.core.WatchState
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Whether there is a watch to talk to right now. */
enum class WatchLinkState { NO_WATCH, NO_APP, CONNECTED }

/** A command for one app that the watch has not picked up, or never did. */
sealed interface WatchRequest {
    data object Sending : WatchRequest
    data class Failed(val message: String) : WatchRequest
}

/**
 * The phone's view of Depot on the watch: what the watch last reported, and commands to it.
 * The watch downloads and installs by itself; only small commands and its state cross the link.
 * Every failure here ends as "no watch", so a phone without Play services or a watch is unaffected.
 */
class WatchRemote(private val context: Context, private val scope: CoroutineScope) {
    private data class Report(val stamp: Long, val state: WatchState)

    private val reports = MutableStateFlow<Map<String, Report>>(emptyMap())
    private val reachable = MutableStateFlow<Set<String>>(emptySet())
    private val listening = AtomicBoolean(false)

    private val _link = MutableStateFlow(WatchLinkState.NO_WATCH)
    val link: StateFlow<WatchLinkState> = _link

    /** Null until a watch has reported. Can be old when [link] is not CONNECTED. */
    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state

    /** By repository key. */
    private val _requests = MutableStateFlow<Map<String, WatchRequest>>(emptyMap())
    val requests: StateFlow<Map<String, WatchRequest>> = _requests

    // Kept as fields so the listeners live as long as this object does.
    private val onData = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            val item = event.dataItem
            if (item.uri.path != WatchLink.STATE_PATH) continue
            if (event.type == DataEvent.TYPE_DELETED) forget(item.uri) else accept(item)
        }
    }
    private val onCapability = CapabilityClient.OnCapabilityChangedListener {
        scope.launch(Dispatchers.IO) { findWatches() }
    }

    /** Starts listening the first time, then re-reads who is connected. Call when the screen is shown. */
    fun refresh() {
        if (listening.compareAndSet(false, true)) {
            try {
                Wearable.getDataClient(context).addListener(
                    onData, Uri.parse("wear://*${WatchLink.STATE_PATH}"), DataClient.FILTER_LITERAL,
                )
                Wearable.getCapabilityClient(context).addListener(onCapability, WatchLink.CAPABILITY)
            } catch (e: Exception) {
                // No Play services. The reads below fail the same way and leave "no watch".
            }
            scope.launch(Dispatchers.IO) { readReports() }
        }
        scope.launch(Dispatchers.IO) { findWatches() }
    }

    /** Asks the watch to look for new releases. Also wakes a watch app that is not running, so it reports. */
    fun check() {
        scope.launch(Dispatchers.IO) { send(WatchCommand.Check(newId())) }
    }

    /** Asks the watch to install or update the app of this repository. */
    fun install(key: String) {
        if (_requests.value[key] == WatchRequest.Sending) return
        setRequest(key, WatchRequest.Sending)
        scope.launch(Dispatchers.IO) {
            val command = WatchCommand.Install(key, newId())
            val error = send(command)
            if (error != null) {
                setRequest(key, WatchRequest.Failed(error))
                return@launch
            }
            // The watch repeats the id in its next report once it has started on the command.
            val answered = withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                reports.first { all -> all.values.any { it.state.ack == command.id } }
            }
            setRequest(
                key,
                if (answered != null) null
                else WatchRequest.Failed("The watch did not answer. Open Depot on the watch and try again."),
            )
        }
    }

    /** Blocks. Returns null when at least one watch took the message, otherwise a message for the user. */
    private fun send(command: WatchCommand): String? {
        val nodes = findWatches()
        if (nodes.isEmpty()) return "No watch connected. Bring the watch in range and try again."
        val data = WatchLink.encodeCommand(command)
        val client = Wearable.getMessageClient(context)
        var failure: Exception? = null
        var sent = 0
        for (node in nodes) {
            try {
                Tasks.await(client.sendMessage(node, WatchLink.COMMAND_PATH, data), 10, TimeUnit.SECONDS)
                sent++
            } catch (e: Exception) {
                failure = e
            }
        }
        if (sent > 0) return null
        return "Could not reach the watch: ${failure?.cause?.message ?: failure?.message ?: "unknown error"}"
    }

    /** Blocks. Returns the ids of the connected nodes that have the Depot watch app. */
    private fun findWatches(): Set<String> {
        val nodes = try {
            val info = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(WatchLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                10, TimeUnit.SECONDS,
            )
            info.nodes.map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        _link.value = when {
            nodes.isNotEmpty() -> WatchLinkState.CONNECTED
            // A watch without the capability has no Depot, or one from before it took commands.
            WatchSync.watches(context).isNotEmpty() -> WatchLinkState.NO_APP
            else -> WatchLinkState.NO_WATCH
        }
        reachable.value = nodes
        publish()
        return nodes
    }

    /** Blocks. Reads the reports that arrived while this app was not running. */
    private fun readReports() {
        try {
            val items = Tasks.await(
                Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${WatchLink.STATE_PATH}")),
                10, TimeUnit.SECONDS,
            )
            try {
                items.forEach { accept(it) }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            // Stays "waiting for the watch".
        }
    }

    private fun accept(item: DataItem) {
        val node = item.uri.host ?: return
        val map = try {
            DataMapItem.fromDataItem(item).dataMap
        } catch (e: Exception) {
            return
        }
        val state = WatchLink.decodeState(map.getString("state")) ?: return
        reports.update { it + (node to Report(map.getLong("stamp", 0), state)) }
        // A request that timed out was only slow if the watch is working on that app after all.
        _requests.update { all ->
            all.filterNot { (key, request) ->
                request is WatchRequest.Failed && state.app(key).let { it != null && (it.busy != null || it.needsUser) }
            }
        }
        publish()
    }

    private fun forget(uri: Uri) {
        val node = uri.host ?: return
        reports.update { it - node }
        publish()
    }

    /** With more than one watch, shows a connected one, and of those the one that reported last. */
    private fun publish() {
        val all = reports.value
        val connected = all.filterKeys { it in reachable.value }
        _state.value = connected.ifEmpty { all }.values.maxByOrNull { it.stamp }?.state
    }

    private fun setRequest(key: String, request: WatchRequest?) {
        _requests.update { if (request == null) it - key else it + (key to request) }
    }

    private fun newId() = Random.nextLong(1, Long.MAX_VALUE)

    private companion object {
        // Before it answers, the watch may have to start, read the phone's settings and ask GitHub.
        const val ANSWER_TIMEOUT_MS = 45_000L
    }
}
