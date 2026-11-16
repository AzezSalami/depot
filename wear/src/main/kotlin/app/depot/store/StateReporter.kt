package app.depot.store

import android.content.Context
import android.os.SystemClock
import app.depot.core.Depot
import app.depot.core.WatchLink
import app.depot.core.WatchState
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val PROGRESS_INTERVAL_MS = 1000L

/** Keeps the `/depot/watch-state` data item equal to what this watch knows, so the phone can show it. */
class StateReporter(private val context: Context, private val depot: Depot) {
    private val ack = MutableStateFlow(0L)

    /** Tells the phone that the command with this id arrived. */
    fun acknowledge(id: Long) {
        // 0 is a command nobody waits for: one from an older phone app, or one the watch gave itself.
        if (id != 0L) ack.value = id
    }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var written: WatchState? = null
            var writtenAt = 0L
            depot.apps.combine(ack) { apps, id -> WatchLink.stateOf(apps, id) }.collectLatest { state ->
                val last = written
                if (state == last) return@collectLatest
                if (last != null && WatchLink.onlyProgressChanged(last, state)) {
                    // A newer value cancels this wait and starts its own, measured from the same
                    // last write, so progress goes out about once a second and the final value always does.
                    delay((writtenAt + PROGRESS_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
                if (write(state)) {
                    written = state
                    writtenAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun write(state: WatchState): Boolean = try {
        val request = PutDataMapRequest.create(WatchLink.STATE_PATH).apply {
            dataMap.putString("state", WatchLink.encodeState(state))
            // Lets a phone that has seen two watches tell which report is the newer one.
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(context).putDataItem(request), 10, TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        // No Play services or no phone: the watch still works on its own.
        false
    }
}
