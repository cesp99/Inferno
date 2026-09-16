package to.eyed.inferno

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.ModelRepository

/**
 * Application root. Builds the [AppContainer] lazily so a process started by DownloadService alone
 * still gets a repository, creates the notification channels, wires memory-trim + process-lifecycle hooks and
 * runs the orphan-attachment sweep once per process.
 */
class InfernoApp : Application(), DownloadService.Host, SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override val modelRepository: ModelRepository get() = container.models

    override fun onCreate() {
        super.onCreate()
        val c = container
        c.notifications.ensureChannels()
        // ON_START/ON_STOP -> download auto-resume. The engine registers its own observer for the foreground-service
        // policy (InferenceEngine.init), so it is deliberately not forwarded twice from here.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> c.models.onAppForeground(true)
                Lifecycle.Event.ON_STOP -> c.models.onAppForeground(false)
                else -> Unit
            }
        })
        // Attachments picked but never sent, or lost to process death before send: gone after 24 h without a row.
        c.appScope.launch(Dispatchers.IO) {
            runCatching { c.images.sweepOrphans(refsOf = { id -> c.db.dao().imageRefs(id) }) }
                .onFailure { Log.w(TAG, "orphan sweep failed: ${it.message}") }
        }
    }

    /** keepModelLoaded decides whether a BACKGROUND trim unloads or only releases the context. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.engine.onTrimMemory(level, container.prefs.settings.value.keepModelLoaded)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .build()

    private companion object { const val TAG = "Inferno/App" }
}
