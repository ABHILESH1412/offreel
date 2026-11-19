package dev.offreel

import android.app.Application
import android.content.Context
import dev.offreel.data.OffreelDb
import dev.offreel.data.Repository
import dev.offreel.data.SettingsStore
import dev.offreel.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OffreelApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch { graph.repository.defaultGroupId() }   // make sure Default exists
    }
}

/** The app's few long-lived objects, created once per process. */
class AppGraph(app: Application) {
    val db = OffreelDb.create(app)
    val settings = SettingsStore(app)
    val downloads = Downloads(app, settings)
    val repository = Repository(db, downloads)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

val Context.graph: AppGraph get() = (applicationContext as OffreelApp).graph
