package app.depot.store

import android.app.Application
import app.depot.core.Depot
import app.depot.core.Variant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DepotApp : Application() {
    /** Outlives screens, so a download keeps going through rotation or leaving the app. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var depot: Depot
        private set

    override fun onCreate() {
        super.onCreate()
        depot = Depot.init(this, Variant.PHONE)
        depot.onConfigChanged = { scope.launch(Dispatchers.IO) { WatchSync.push(this@DepotApp, depot.settings) } }
    }
}
