package app.depot.store

import android.app.Application
import app.depot.core.Depot
import app.depot.core.Variant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DepotWearApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var depot: Depot
        private set

    override fun onCreate() {
        super.onCreate()
        depot = Depot.init(this, Variant.WEAR)
    }
}
