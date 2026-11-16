package app.depot.store

import android.app.Application
import app.depot.core.Busy
import app.depot.core.Depot
import app.depot.core.Variant
import app.depot.core.WatchCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class DepotWearApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var depot: Depot
        private set
    private lateinit var reporter: StateReporter
    private val installing = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        depot = Depot.init(this, Variant.WEAR)
        reporter = StateReporter(this, depot).also { it.start(scope) }
    }

    /**
     * Runs a command from the phone. It can arrive with no activity alive, so the work lives on the
     * application scope. The id is acknowledged once the work shows in the app list, so the phone
     * goes straight from "sending" to the progress.
     */
    fun handle(command: WatchCommand) {
        scope.launch {
            try {
                when (command) {
                    is WatchCommand.Check -> {
                        reporter.acknowledge(command.id)
                        if (!depot.refreshing.value) depot.refresh()
                    }
                    is WatchCommand.Install -> install(command)
                }
            } catch (e: Exception) {
                // Depot records what went wrong per app; a stray failure must not take the process down.
                reporter.acknowledge(command.id)
            }
        }
    }

    private suspend fun install(command: WatchCommand.Install) {
        val key = command.key
        // A second tap on the phone while the first is still being prepared.
        if (!installing.add(key)) {
            reporter.acknowledge(command.id)
            return
        }
        try {
            // The phone may have added this repository while this process was not running.
            if (state(key) == null) withContext(Dispatchers.IO) { pullConfig(this@DepotWearApp) }
            if (state(key)?.asset == null) depot.refresh()
            val busy = state(key)?.busy
            reporter.acknowledge(command.id)
            if (busy is Busy.Downloading || busy == Busy.Installing) return
            // Result ignored on purpose: failures and "needs a tap" are in the app list, which is reported.
            depot.install(key)
        } finally {
            installing.remove(key)
        }
    }

    private fun state(key: String) = depot.apps.value.firstOrNull { it.repo.key == key }
}
