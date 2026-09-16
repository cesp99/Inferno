package to.eyed.inferno

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import to.eyed.inferno.data.AppPrefs
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.data.GeneratedImageStoreImpl
import to.eyed.inferno.data.ImageUtil
import to.eyed.inferno.data.db.ChatDatabase
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.CpuTopology
import to.eyed.inferno.engine.EngineJob
import to.eyed.inferno.engine.EngineService
import to.eyed.inferno.engine.InferenceEngine
import to.eyed.inferno.engine.ThermalGovernor
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.imagegen.DefaultImageModelFiles
import to.eyed.inferno.imagegen.ImageEngineAdapter
import to.eyed.inferno.imagegen.ImageGenRepository
import to.eyed.inferno.models.AndroidDownloadPlatform
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.EngineAccess
import to.eyed.inferno.models.ModelDownloader
import to.eyed.inferno.models.ModelFiles
import to.eyed.inferno.models.ModelRepository
import to.eyed.inferno.sd.ImageEngine
import java.util.concurrent.TimeUnit

/**
 * Composition root: the only file that constructs the other packages. No DI framework; everything
 * is a plain `val` created in dependency order. Construction touches no native code: the CPU gate
 * ([isCpuSupported]) is read from /proc before anything could call System.loadLibrary, and both engines bind
 * their JNI objects lazily on first use, so an unsupported phone can still render UnsupportedCpuScreen.
 */
class AppContainer(app: Application) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val cpu = CpuTopology(app)
    /** dotprod + fp16: the .so files are built with -march=armv8.2-a+dotprod+fp16 and would SIGILL without them. */
    val isCpuSupported: Boolean = cpu.isSupported

    val prefs = AppPrefs(app)
    val prefsLoaded: Boolean get() = prefs.loaded.value

    val thermal = ThermalGovernor(app, appScope)
    val engine = InferenceEngine(cpu, thermal, appScope, app)
    val contextManager = ContextManager(engine)

    val db: ChatDatabase = ChatDatabase.create(app)
    val images = ImageUtil(app)
    val chats = ChatRepository(db.dao(), images)

    val modelFiles = ModelFiles(app)
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val downloader = ModelDownloader(okHttp, modelFiles)

    /** ModelRepository's view of the text engine (delete/deleteAll never pull the file from under a live model). */
    private val engineAccess = object : EngineAccess {
        override val loadedModelId: String? get() = engine.state.value.modelOrNull?.id
        override val isGenerating: Boolean get() = engine.state.value is to.eyed.inferno.engine.EngineState.Generating
        override suspend fun unload() = engine.unload()
        override suspend fun clearEstimateCache() = engine.clearEstimateCache()
    }
    val models = ModelRepository(modelFiles, downloader, engineAccess, prefs, AndroidDownloadPlatform(app), appScope)

    // ---- image generation ----
    val imageEngine = ImageEngine(threads = IMAGE_THREADS, context = app)
    val generatedImages = GeneratedImageStoreImpl(db.generatedImages(), images)
    val imageGen = ImageGenRepository(
        engine = ImageEngineAdapter(imageEngine),
        files = DefaultImageModelFiles(modelFiles.root),
        coordinator = engine,                       // releaseForImageGen == InferenceEngine.unload()
        jobGate = EngineJob,                        // the one process-wide native-job mutex
        store = generatedImages,
        etaStore = prefs,
        scope = appScope,
        // Same budget the LLM planner uses (availMem minus the LMK margin); the repository applies the 0.85 gate.
        memoryBudgetBytes = { ContextManager.budgetBytes(cpu.availRamBytes()) },
    )

    val notifications = Notifications(app)

    init {
        // Mirror image of releaseForImageGen: stable-diffusion.cpp is out of memory before the text weights load.
        engine.beforeLoad = { imageGen.unload() }
    }

    companion object {
        /** Fixed at load time by sd.cpp; 4 = the A78 cluster (8 with the A55s is slower). */
        const val IMAGE_THREADS = 4
    }
}

/** The one place that knows about notification channels and whether the shade will show anything at all. */
class Notifications(private val context: Context) {
    /** Both channels are IMPORTANCE_LOW: silent, no badge. Idempotent; the services also call these on a cold start. */
    fun ensureChannels() {
        EngineService.ensureChannel(context)
        DownloadService.ensureChannel(context)
    }

    /** POST_NOTIFICATIONS granted (API 33+ runtime permission) and the app not muted in system settings. */
    val enabled: Boolean
        get() = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
}
