package to.eyed.inferno.imagegen

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.sd.ImageEngine
import to.eyed.inferno.sd.ImageEngineState
import to.eyed.inferno.sd.ImageGenEvent
import to.eyed.inferno.sd.ImageGenRequest
import to.eyed.inferno.sd.ImageModelSpec

/**
 * App-side owner of the image engine. One generation at a time: it releases the LLM engine first
 * ([EngineCoordinator]), takes the process-wide native-job mutex ([JobGate]), loads the model if it is
 * not already resident, streams progress into [state] with an ETA, and persists the result through
 * [GeneratedImageStore]. The model stays loaded after a run so "Regenerate" is instant; the LLM side
 * calls [unload] through the same coordinator pattern before it loads a text model.
 *
 * @param memoryBudgetBytes Device memory budget used by the 0.85 x refusal rule;
 *   the default never refuses (the engine's own 500 MB watchdog still protects the run).
 */
class ImageGenRepository(
    private val engine: ImageEngineApi,
    private val files: ImageModelFiles,
    private val coordinator: EngineCoordinator,
    private val jobGate: JobGate,
    private val store: GeneratedImageStore,
    private val etaStore: EtaStore,
    private val scope: CoroutineScope,
    private val memoryBudgetBytes: () -> Long = { Long.MAX_VALUE },
    private val catalog: List<ImageCatalogModel> = ImageModelCatalog.models,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ImageGenUiState>(ImageGenUiState.Idle)
    val state: StateFlow<ImageGenUiState> = _state.asStateFlow()

    val eta = EtaModel(catalog)

    /** Mirrors the engine so the UI can show "model loaded" without reaching into :sdengine. */
    val engineState: StateFlow<ImageEngineState> get() = engine.state

    private var job: Job? = null

    /** True while a generation (including its load phase) is running; the chat composer disables Send. */
    val isBusy: Boolean get() = job?.isActive == true

    init {
        // Persisted observations arrive asynchronously; until then the seeds give a sane first estimate.
        scope.launch { runCatching { etaStore.load() }.onSuccess(eta::merge) }
    }

    fun modelById(id: String): ImageCatalogModel? = catalog.firstOrNull { it.id == id }

    fun isDownloaded(model: ImageCatalogModel): Boolean =
        files.modelPath(model) != null && files.taesdPath() != null

    /** Whole-run estimate for the model card and the Generate button ("~12 s" / "~1 min"). */
    fun estimateSeconds(modelId: String, size: ImageSizePreset, steps: Int? = null): Int {
        val model = modelById(modelId)
        val n = model?.clampSteps(steps ?: model.defaultSteps) ?: (steps ?: 1)
        return wholeSeconds(eta.estimateSeconds(modelId, size.px, n))
    }

    /**
     * Starts a generation; progress is observed through [state]. Returns null (and leaves [state] telling
     * why) when nothing was started: a run is already active, the model is unknown, or files are missing.
     */
    fun generate(params: ImageGenParams): Job? {
        if (isBusy) return null
        val model = modelById(params.modelId)
        if (model == null) {
            _state.value = ImageGenUiState.Error(Msg.UNKNOWN_MODEL)
            return null
        }
        val modelPath = files.modelPath(model)
        val taesdPath = files.taesdPath()
        if (modelPath == null || taesdPath == null) {
            _state.value = ImageGenUiState.NeedsDownload(model.id)
            return null
        }
        val steps = model.clampSteps(params.steps ?: model.defaultSteps)
        return scope.launch { run(model, modelPath, taesdPath, params, steps) }.also { job = it }
    }

    /** Requests cancellation; the native job is aborted within one step and joined by the flow's finally. */
    fun cancel() {
        val j = job
        j?.cancel()
        // Nothing running but the UI still says busy (a run that ended without a terminal event): settle it here,
        // so the Stop button always does something visible.
        if ((j == null || j.isCompleted) && _state.value.let { it is ImageGenUiState.Loading || it is ImageGenUiState.Generating }) _state.value = ImageGenUiState.Idle
    }

    suspend fun cancelAndJoin() {
        job?.let { it.cancel(); it.join() }
    }

    /** Cancels any run and frees the sd.cpp context (called before the LLM engine loads a model). */
    suspend fun unload() {
        cancelAndJoin()
        engine.unload()
    }

    /** Clears a terminal Done / Error / NeedsDownload state back to Idle (e.g. when leaving the screen). */
    fun reset() {
        if (!isBusy) _state.value = ImageGenUiState.Idle
    }

    // ------------------------------------------------------------------------------------------

    private suspend fun run(
        model: ImageCatalogModel,
        modelPath: String,
        taesdPath: String,
        params: ImageGenParams,
        steps: Int,
    ) {
        _state.value = ImageGenUiState.Loading
        // Keep-alive bracket around the whole run (waiting for the job included): the coordinator starts the
        // foreground service only if the app is backgrounded while this is active, and stops it when it ends.
        coordinator.onImageJob(active = true, modelName = model.displayName)
        try {
            jobGate.withJob(JOB_TAG) {
                coordinator.releaseForImageGen()
                ensureLoaded(model, modelPath, taesdPath)
                val req = ImageGenRequest(
                    prompt = params.prompt,
                    negativePrompt = "",            // hidden in the UI: cfg 1.0 makes sd.cpp ignore it
                    width = params.size.px,
                    height = params.size.px,
                    steps = steps,
                    cfgScale = model.spec.cfgScale,
                    seed = params.seed,
                    sampler = model.spec.sampler,
                    scheduler = model.spec.scheduler,
                    previews = steps > 1,
                )
                collectRun(model, params, req)
                // The flow contract is exactly one Done / Error; should it ever end without one, the screen must
                // not sit on "Starting" forever with a Stop button that does nothing.
                if (_state.value.let { it is ImageGenUiState.Loading || it is ImageGenUiState.Generating }) {
                    log("image run ended without a terminal event")
                    _state.value = ImageGenUiState.Error(Msg.FAILED)
                }
            }
        } catch (e: CancellationException) {
            _state.value = ImageGenUiState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = ImageGenUiState.Error(e.message ?: Msg.FAILED)
        } finally {
            coordinator.onImageJob(active = false, modelName = model.displayName)
        }
    }

    private suspend fun ensureLoaded(model: ImageCatalogModel, modelPath: String, taesdPath: String) {
        val current = engine.state.value
        if (current is ImageEngineState.Ready && current.modelId == model.id) return
        if (current is ImageEngineState.Ready || current is ImageEngineState.Error) engine.unload()

        val need = engine.estimateBytes(model.spec, File(modelPath).length())
        val budget = memoryBudgetBytes()
        if (need > budget * MEMORY_FRACTION) throw ImageGenException(Msg.NOT_ENOUGH_MEMORY)

        engine.load(model.spec, modelPath, taesdPath)
        val after = engine.state.value
        if (after !is ImageEngineState.Ready) {
            throw ImageGenException((after as? ImageEngineState.Error)?.message ?: Msg.LOAD_FAILED)
        }
    }

    private suspend fun collectRun(model: ImageCatalogModel, params: ImageGenParams, req: ImageGenRequest) {
        val px = req.width
        var preview: ByteArray? = null
        var firstStep = -1
        var firstMs = 0L
        var lastStep = -1
        var lastMs = 0L
        val startedAt = clock()

        _state.value = ImageGenUiState.Generating(
            0, req.steps, wholeSeconds(eta.estimateSeconds(model.id, px, req.steps)), null,
        )
        engine.generate(req).collect { ev ->
            when (ev) {
                ImageGenEvent.Loading -> Unit
                is ImageGenEvent.Progress -> {
                    if (ev.step > 0) {
                        if (firstStep < 0) { firstStep = ev.step; firstMs = ev.elapsedMs }
                        lastStep = ev.step; lastMs = ev.elapsedMs
                    }
                    ev.previewPng?.let { preview = it }
                    val remaining = eta.remainingSeconds(model.id, px, ev.step, ev.totalSteps)
                    _state.value = ImageGenUiState.Generating(ev.step, ev.totalSteps, wholeSeconds(remaining), preview)
                }
                ImageGenEvent.Decoding -> _state.value = ImageGenUiState.Generating(
                    req.steps, req.steps, wholeSeconds(eta.decodeSeconds(model.id, px)), preview,
                )
                is ImageGenEvent.Done -> {
                    learn(model.id, px, req.steps, firstStep, firstMs, lastStep, lastMs)
                    val meta = GenerationMeta(
                        modelId = model.id,
                        prompt = params.prompt,
                        negative = req.negativePrompt,
                        width = ev.width,
                        height = ev.height,
                        steps = req.steps,
                        seed = ev.seed,
                        sampler = req.sampler.name,
                        totalMs = ev.totalMs,
                        createdAt = startedAt,
                    )
                    // The image exists; losing it to a cancel that lands during the disk write would be worse
                    // than finishing a few ms late.
                    val record = withContext(NonCancellable) { store.save(ev.png, meta) }
                    _state.value = ImageGenUiState.Done(record)
                }
                is ImageGenEvent.Error -> _state.value = ImageGenUiState.Error(ev.message)
            }
        }
    }

    /**
     * Per-step time from the progress timestamps. With two or more step reports the difference is a clean
     * per-step figure; a one-step model only gives "time to the first step", which includes text encoding,
     * and that is also what the user waits for, so it is fed back as-is.
     */
    private suspend fun learn(
        modelId: String, px: Int, steps: Int, firstStep: Int, firstMs: Long, lastStep: Int, lastMs: Long,
    ) {
        val secPerStep = when {
            lastStep > firstStep && firstStep > 0 -> (lastMs - firstMs) / 1000f / (lastStep - firstStep)
            lastStep > 0 -> lastMs / 1000f / lastStep
            else -> return
        }
        if (steps <= 0 || secPerStep <= 0f) return
        eta.observe(modelId, px, secPerStep)
        // Persistence must never fail a generation that already produced an image.
        withContext(NonCancellable) { runCatching { etaStore.save(eta.snapshot()) } }
    }

    private class ImageGenException(message: String) : Exception(message)

    /** android.util.Log throws under the host JVM (unit tests); fall back to println there. */
    private fun log(msg: String) {
        try { android.util.Log.w("Inferno/ImageGen", msg) } catch (e: RuntimeException) { println("W Inferno/ImageGen: $msg") }
    }

    /** UI strings live in ui/Strings.kt; these are the data-layer messages that end up in Error(). */
    private object Msg {
        const val UNKNOWN_MODEL = "Unknown image model"
        const val NOT_ENOUGH_MEMORY = "Not enough memory to load this model"
        const val LOAD_FAILED = "Could not load the image model"
        const val FAILED = "Image generation failed"
    }

    companion object {
        const val JOB_TAG = "imagegen"
        const val MEMORY_FRACTION = 0.85

        /** Rounds up for display, ignoring float noise so a seeded 63.0 never shows as 64. */
        private fun wholeSeconds(s: Float): Int = ceil(s - 0.01f).toInt().coerceAtLeast(0)
    }
}

/** Thin adapter over the real engine so the repository depends on the [ImageEngineApi] seam only. */
class ImageEngineAdapter(private val engine: ImageEngine) : ImageEngineApi {
    override val state: StateFlow<ImageEngineState> get() = engine.state
    override suspend fun load(spec: ImageModelSpec, modelPath: String, taesdPath: String) =
        engine.load(spec, modelPath, taesdPath)
    override fun generate(req: ImageGenRequest): Flow<ImageGenEvent> = engine.generate(req)
    override suspend fun unload() = engine.unload()
    override fun estimateBytes(spec: ImageModelSpec, modelBytes: Long): Long = engine.estimateBytes(spec, modelBytes)
}

/**
 * Default file layout: `<root>/<modelId>/<fileName>` and `<root>/taesd/<fileName>`, where root is
 * `filesDir/models` (the same directory `ModelFiles` manages). A file counts as present only when
 * its length equals the catalog size, so a torn download shows as "Download", never as a crash in sd.cpp.
 */
class DefaultImageModelFiles(private val root: File) : ImageModelFiles {
    override fun modelPath(model: ImageCatalogModel): String? =
        complete(File(File(root, model.id), model.file.fileName), model.file.sizeBytes)

    override fun taesdPath(): String? =
        complete(File(File(root, ImageModelCatalog.TAESD_DIR_ID), ImageModelCatalog.taesd.fileName), ImageModelCatalog.taesd.sizeBytes)

    private fun complete(f: File, expected: Long): String? =
        if (f.isFile && f.length() == expected) f.absolutePath else null
}
