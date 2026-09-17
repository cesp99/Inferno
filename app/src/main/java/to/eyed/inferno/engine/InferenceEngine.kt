package to.eyed.inferno.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.inferno.data.GpuPref
import to.eyed.inferno.data.PerfPreset
import to.eyed.inferno.imagegen.EngineCoordinator
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ThinkingSpec

/**
 * Kotlin owner of the native engine: one dedicated thread ("inferno-llama") for every LlamaNative call, the
 * process-wide [EngineJob] around every native job, a [StateFlow] of [EngineState], and cold [generate] flows.
 * Memory rules: the budget gate refuses loads whose estimate exceeds 0.85 x budget, a 500 ms watchdog
 * cancels any native job when availMem < 500 MB and then unloads, and the thermal governor sets the thread count
 * per turn (and steps it down mid-turn on the cheap llama_set_n_threads path).
 *
 * [context] is optional (null in JVM tests); with it the engine registers on ProcessLifecycleOwner and drives
 * [EngineService] (foreground service only while a text turn or an image run is active in the background).
 */
class InferenceEngine internal constructor(
    private val cpu: CpuTopology,
    private val thermal: ThermalGovernor,
    private val scope: CoroutineScope,
    private val context: Context?,
    private val native: NativeApi,
) : PlannerEngine, EngineCoordinator {
    constructor(cpu: CpuTopology, thermal: ThermalGovernor, scope: CoroutineScope, context: Context? = null) :
        this(cpu, thermal, scope, context, LlamaNativeApi)

    data class BenchResult(val ppTps: Double, val tgTps: Double, val ppMs: Double, val tgMs: Double)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "inferno-llama") }
    private val engine: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val watchdog = MemoryWatchdog(scope) { cpu.availRamBytes() }

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _kvUsed = MutableStateFlow(0)
    /** Tokens in the KV cache, updated after each turn / clear (belongs to whichever chat generated last). */
    val kvUsed: StateFlow<Int> = _kvUsed.asStateFlow()
    val loaded: LoadedModel? get() = _state.value.loadedOrNull

    /** Native log floor (SettingsState.minLogPriority): passed to backendInit, and applied live once the backend is up. */
    @Volatile var minLogPriority: Int = Log.INFO
        set(v) { field = v; if (backendReady) native.setLogPriority(v) }
    /** Performance preset for the ADPF / sustained-mode hints (SettingsState.perfPreset); the VM keeps it current. */
    @Volatile var perfPreset: PerfPreset = PerfPreset.AUTO
    /**
     * GPU policy (SettingsState.gpu): passed to backendInit, then applied live for the next load. Whether a loaded
     * model actually runs on the GPU is [LoadedModel.gpu]; the VM reloads the weights when the two disagree.
     */
    @Volatile var gpuPref: GpuPref = GpuPref.AUTO
    private var appliedGpuPref: GpuPref? = null     // engine thread: what native currently holds
    /**
     * Runs before every [load], outside the engine job: the composition root points it at
     * ImageGenRepository.unload() so stable-diffusion.cpp is out of memory before the text weights (and the
     * budget gate, which reads availMem) run — the mirror image of [releaseForImageGen] (12.5 single-job rule).
     */
    @Volatile var beforeLoad: (suspend () -> Unit)? = null

    // Engine-thread state. `current` is also read from other threads (state snapshots), hence @Volatile.
    private var model = 0L
    private var ctx = 0L
    @Volatile private var current: LoadedModel? = null
    private var params = GenerationParams()
    @Volatile private var backendReady = false
    // Thread count the live context runs with (thermal-adjusted); observable so the Compute row updates when the
    // native change lands a moment after the preference.
    private val _activeThreads = MutableStateFlow(0)
    private var appliedThreads: Int
        get() = _activeThreads.value
        set(v) { _activeThreads.value = v }
    private var mmprojThreads = -1          // thread count the resident projector was created with
    private var mmprojMaxTokens = -1        // image_max_tokens the resident projector was created with (residency key)
    /** Image detail the last load / turn asked for: countPromptTokens (fit) must build the same projector generate() will use. */
    private var requestedDetail: ImageDetail = ImageDetail.BALANCED

    @Volatile private var generating = false
    @Volatile private var imageJobActive = false
    @Volatile private var imageJobTitle: String? = null
    @Volatile private var foreground = true
    @Volatile private var serviceStarted = false

    init {
        if (context != null) Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> onAppForeground(true)
                    Lifecycle.Event.ON_STOP -> onAppForeground(false)
                    else -> Unit
                }
            })
        }
    }

    fun systemInfo(): String = native.systemInfo()

    /**
     * The OpenCL GPU as probed at backend init: null until then, [GpuInfo.available] false without a driver, [GpuInfo.usable]
     * false when ggml-opencl dropped the device (Mali: no kernels), [GpuInfo.active] when the policy would offload the next load.
     */
    data class GpuInfo(val available: Boolean, val name: String, val version: String, val driver: String, val adreno: Boolean, val usable: Boolean, val active: Boolean)
    fun gpuInfo(): GpuInfo? {
        if (!backendReady) return null
        val f = native.gpuInfo().split('\t')
        return if (f.size < 6) GpuInfo(false, "", "", "", adreno = false, usable = false, active = false)
        else GpuInfo(true, f[0], f[1], f[2], adreno = f[3] == "1", usable = f[4] == "1", active = f[5] == "1")
    }

    // ---- developer-mode facts (ui/settings/DeveloperPage.kt); snapshot reads, any thread ----
    /** Thread count the live context runs with right now (thermal-adjusted), 0 without a context. */
    val activeThreads: Int get() = appliedThreads
    val activeThreadsFlow: StateFlow<Int> = _activeThreads.asStateFlow()
    /** Buffer types the last load put the weights in, e.g. "CPU_KLEIDIAI 1.1 GB · CPU_Mapped 0.3 GB"; "" before any load. */
    fun modelBufferTypes(): String = if (!backendReady) "" else parseBufferTypes(native.modelBufferTypes())

    /** Any thread: asks the native loop to stop (generation, prefill, image encode, bench, calibration). */
    fun cancel() = native.cancel()

    // ---------------------------------------------------------------------------------------------------------
    // Model lifecycle

    /** Engine thread; exact native (MemEst.*) + encoderPeakBytes = catalog row (imports: 700 MB) for vision models, 0 for text-only. */
    override suspend fun estimateMemory(model: LocalModel, config: ContextConfig): MemoryEstimate =
        EngineJob.withJob("llm-estimate") { withContext(engine) { estimateLocked(model, config) } }

    /** Loads text weights (projector stays lazy), creates the context from [config], sets sampler defaults. Emits Loading phases. */
    suspend fun load(model: LocalModel, config: ContextConfig, imageDetail: ImageDetail, useMmap: Boolean, visionAllowed: Boolean): LoadedModel {
        beforeLoad?.invoke()
        return EngineJob.withJob("llm-load") {
            // Keep the no_alloc twin ContextManager.plan() just built: the budget gate below hits the cache instead of
            // re-parsing the GGUF (~1 s); native model_load drops that entry once the real model supersedes it.
            withContext(NonCancellable + engine) { unloadLocked(clearEstimates = false) }
            _state.value = EngineState.Loading(model, null, LoadPhase.WEIGHTS)
            requestedDetail = imageDetail
            val t0 = System.nanoTime()
            var lowMemory = false
            coroutineScope {
                // The native loader has no cancel flag: a cancelled caller (user picked another model while loading) aborts
                // it through the progress callback. The watcher flips the flag the moment this scope is cancelled.
                var cancelled = false
                var finished = false
                val watcher = launch { try { awaitCancellation() } finally { if (!finished) { cancelled = true; native.cancel() } } }
                try {
                    watchdog.guard(onLowMemory = { lowMemory = true; native.cancel() }) {
                        withContext(engine) {
                            ensureBackendLocked()
                            // Budget gate: never start a load the planner would have refused.
                            val est = estimateLocked(model, config)
                            val avail = cpu.availRamBytes()
                            val need = if (visionAllowed && model.hasVision) est.totalBytes else est.totalBytes - est.visionBytes
                            val gate = (ContextManager.BUDGET_GATE * ContextManager.budgetBytes(avail)).toLong()
                            if (need > gate) throw BudgetGateException("Not enough memory: needs about ${ContextManager.gb(ContextManager.freeNeededFor(need))} GB free, ${ContextManager.gb(avail)} GB available")
                            val catalog = model.catalog
                            this@InferenceEngine.model = native.modelLoad(model.textPath, null, useMmap, config.nThreads,
                                imageMinTokens(model), imageMaxTokens(model, imageDetail)) { done, total, _ ->
                                _state.value = EngineState.Loading(model, if (total > 0) done.toFloat() / total else null, LoadPhase.WEIGHTS)
                                !lowMemory && !cancelled
                            }
                            ensureActive()
                            if (this@InferenceEngine.model == 0L) throw EngineException(if (lowMemory) NOT_ENOUGH_MEMORY else nativeError("Could not load the model"))
                            val nums = native.modelInfoNumbers(this@InferenceEngine.model)
                            val strs = native.modelInfoStrings(this@InferenceEngine.model).map { Utf8.decode(it) }
                            _state.value = EngineState.Loading(model, null, LoadPhase.CONTEXT)
                            ctx = native.contextCreate(this@InferenceEngine.model, config.toNative())
                            if (ctx == 0L) throw EngineException(nativeError("Could not create the context"))
                            appliedThreads = config.nThreads
                            native.samplerSet(ctx, params.toNativeFloats(), params.toNativeInts(), null)
                            current = LoadedModel(
                                model = model, arch = strs[MInfoS.ARCH], description = strs[MInfoS.DESC],
                                nParams = nums[MInfo.N_PARAMS], sizeBytes = nums[MInfo.SIZE_BYTES], nCtxTrain = nums[MInfo.N_CTX_TRAIN].toInt(),
                                nLayer = nums[MInfo.N_LAYER].toInt(), nEmbd = nums[MInfo.N_EMBD].toInt(), nHeadKv = nums[MInfo.N_HEAD_KV].toInt(),
                                nSwa = nums[MInfo.N_SWA].toInt(),
                                hasVision = model.hasVision,                // MInfo.HAS_VISION is 1 only while a projector is resident
                                visionAllowed = visionAllowed && model.hasVision, visionResident = false,
                                templateSupported = nums[MInfo.TEMPLATE_SUPPORTED] == 1L, templateName = strs[MInfoS.TEMPLATE_NAME].ifBlank { null },
                                context = config.copy(nCtx = native.contextNCtx(ctx)), estimate = est,
                                loadMs = (System.nanoTime() - t0) / 1_000_000, calibration = null,
                                gpu = gpuInfo()?.takeIf { it.available && nums[MInfo.ON_GPU] == 1L }?.name,
                            )
                            _kvUsed.value = 0
                            EngineLog.i(TAG, "loaded ${model.id} (${strs[MInfoS.ARCH]}, ${catalog?.family ?: "import"}) nCtx=${current!!.context.nCtx} in ${current!!.loadMs} ms")
                        }
                    }
                    finished = true
                    watcher.cancel()
                    val l = current!!
                    _state.value = EngineState.Ready(l)
                    l
                } catch (e: CancellationException) {
                    finished = true
                    withContext(NonCancellable + engine) { unloadLocked() }
                    _state.value = EngineState.Idle
                    throw e
                } catch (e: Throwable) {
                    finished = true
                    watcher.cancel()
                    val msg = if (lowMemory) NOT_ENOUGH_MEMORY else (e as? EngineException)?.message ?: "Could not load the model: ${e.message}"
                    withContext(NonCancellable + engine) { unloadLocked() }
                    _state.value = EngineState.Error(msg, model)
                    if (e is BudgetGateException) throw e
                    throw EngineException(msg)
                }
            }
        }
    }

    /** Recreate only the context (new nCtx/kv type/threads) keeping the weights. */
    suspend fun reconfigure(config: ContextConfig): LoadedModel = EngineJob.withJob("llm-reconfigure") {
        withContext(engine) {
            val l = current ?: throw EngineException("No model loaded")
            _state.value = EngineState.Loading(l.model, null, LoadPhase.CONTEXT)
            if (ctx != 0L) { native.contextFree(ctx); ctx = 0L }
            ctx = native.contextCreate(model, config.toNative())
            if (ctx == 0L) {
                val msg = nativeError("Could not create the context")
                // The old context is gone (freed above) and resumeLocked() will recreate `current`'s config, not the
                // refused one: publish exactly that so fit() and the chip never advertise a size the engine cannot run.
                _kvUsed.value = 0
                appliedThreads = 0
                _state.value = EngineState.Suspended(l, visionReleased = !native.mmprojLoaded(model))
                throw EngineException(msg)
            }
            appliedThreads = config.nThreads
            native.samplerSet(ctx, params.toNativeFloats(), params.toNativeInts(), null)
            _kvUsed.value = 0
            val updated = l.copy(context = config.copy(nCtx = native.contextNCtx(ctx)), visionResident = native.mmprojLoaded(model))
            current = updated
            _state.value = EngineState.Ready(updated)
            updated
        }
    }

    /**
     * Frees the context and the weights (state Idle). A running text job is cancelled first and the unload waits
     * for it to leave the engine thread. Callable from inside another EngineJob holder (image generation).
     */
    suspend fun unload() {
        // Only poke the native cancel flag when one of our jobs is actually running: the flag is sticky until the
        // next generate/count/bench resets it, so a gratuitous cancel() could abort the very next image encode.
        if (EngineJob.owner.value?.startsWith("llm-") == true) native.cancel()
        EngineJob.withJob("llm-unload") {
            withContext(NonCancellable + engine) { unloadLocked() }
            _state.value = EngineState.Idle
        }
    }

    /** EngineCoordinator (imagegen): the LLM must be out of memory before stable-diffusion.cpp allocates. */
    override suspend fun releaseForImageGen() = unload()

    /** models.EngineAccess: the no_alloc estimate cache keeps the GGUF open, so a delete drops it first (engine thread). */
    suspend fun clearEstimateCache() = EngineJob.withJob("llm-estimate-clear") {
        withContext(engine) { if (backendReady) native.estimateCacheClear() }
    }

    suspend fun setSampling(params: GenerationParams) = EngineJob.withJob("llm-sampling") {
        withContext(engine) {
            this@InferenceEngine.params = params
            if (ctx != 0L && !native.samplerSet(ctx, params.toNativeFloats(), params.toNativeInts(), null)) throw EngineException(nativeError("Invalid sampling settings"))
        }
    }

    /** Rebuilds threadpools when needed; the projector, if resident, is re-created lazily before the next image turn. */
    suspend fun setThreads(n: Int, pinBig: Boolean, poll: Int) = EngineJob.withJob("llm-threads") {
        withContext(engine) {
            val l = current ?: return@withContext
            val cfg = l.context.copy(nThreads = n, nThreadsBatch = n, bigCoresOnly = pinBig, poll = poll)
            if (ctx != 0L) {
                if (!native.contextSetThreads(ctx, n, n, pinBig, poll)) throw EngineException(nativeError("Could not change threads"))
                appliedThreads = n
            }
            updateCurrent(l.copy(context = cfg))
        }
    }

    /** Stores the auto-bench result on the loaded model (AppViewModel persists it in prefs). */
    fun setCalibration(calibration: Calibration) { current?.let { updateCurrent(it.copy(calibration = calibration)) } }

    override suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>): Int =
        EngineJob.withJob("llm-count") {
            withContext(engine) {
                val l = current ?: throw EngineException("No model loaded")
                // The `current == null` check stays outside the try: restoreStateLocked() must never publish Idle for
                // a model that was never loaded. Inside, resume / projector load / count can each fail and the state
                // must still leave Loading (generate() and bench() do the same), or every consumer waits forever.
                try {
                    if (ctx == 0L) resumeLocked()
                    // Same thread count the turn will run with, so the projector is not rebuilt twice per thermal change.
                    thermal.refreshHeadroom()
                    applyThermalThreadsLocked(l.context.nThreads)
                    if (images.isNotEmpty()) ensureVisionLocked(requestedDetail)
                    val n = native.promptTokenCount(ctx, messages.toNative(), images.map { it.copy(rgb = null) }.toNative())
                    if (n < 0) throw EngineException(nativeError("Could not count tokens"))
                    n
                } finally {
                    restoreStateLocked()
                }
            }
        }

    suspend fun clearKv() = EngineJob.withJob("llm-kvclear") {
        withContext(engine) { if (ctx != 0L) native.kvClear(ctx); _kvUsed.value = 0 }
    }

    /** Ensures the projector is resident (mmprojLoad); no-op for text-only models. generate() does this itself. */
    suspend fun ensureVision(imageDetail: ImageDetail) = EngineJob.withJob("llm-vision") {
        withContext(engine) {
            val l = current ?: return@withContext
            if (!l.model.hasVision) return@withContext
            requestedDetail = imageDetail
            try {
                if (ctx == 0L) resumeLocked()
                thermal.refreshHeadroom()
                applyThermalThreadsLocked(l.context.nThreads)
                ensureVisionLocked(imageDetail)
            } finally {
                restoreStateLocked()
            }
        }
    }

    /** Calibration / Bench screen. Cancelling the caller aborts the native loop (bench honours the cancel flag) and rethrows. */
    suspend fun bench(nPrompt: Int = 512, nGen: Int = 128, reps: Int = 3, progress: (Int, Int) -> Unit): BenchResult =
        EngineJob.withJob("llm-bench") {
            var lowMemory = false
            coroutineScope {
                var finished = false
                val watcher = launch { try { awaitCancellation() } finally { if (!finished) native.cancel() } }
                try {
                    watchdog.guard(onLowMemory = { lowMemory = true; native.cancel() }) {
                        withContext(engine) {
                            val l = current ?: throw EngineException("No model loaded")
                            if (ctx == 0L) resumeLocked()
                            _state.value = EngineState.Loading(l.model, null, LoadPhase.CALIBRATING)
                            val r = native.bench(ctx, nPrompt, nGen, reps) { done, total, _ -> progress(done, total); !lowMemory }
                            _kvUsed.value = 0
                            ensureActive()
                            if (r.size < 4) throw EngineException(if (lowMemory) NOT_ENOUGH_MEMORY else nativeError("Benchmark cancelled"))
                            BenchResult(r[0], r[1], r[2], r[3])
                        }
                    }
                } finally {
                    finished = true
                    watcher.cancel()
                    withContext(NonCancellable + engine) { if (lowMemory) unloadLocked(NOT_ENOUGH_MEMORY) else restoreStateLocked() }
                }
            }
        }

    // ---------------------------------------------------------------------------------------------------------
    // Generation

    /**
     * Streams one assistant turn. Cold flow; runs on the engine thread; holds the engine job; cancelling the
     * collector calls LlamaNative.cancel() and waits for the native loop to exit. Exactly one terminal event
     * (Done / Error / NeedsTruncation) unless the collector cancels.
     */
    fun generate(conversationId: String, messages: List<PromptMessage>, images: List<PromptImage>,
                 params: GenerationParams, thinking: ThinkingSpec, thinkingEnabled: Boolean, imageDetail: ImageDetail): Flow<GenerationEvent> = channelFlow {
        var terminalSent = false
        suspend fun terminal(e: GenerationEvent) { if (!terminalSent) { terminalSent = true; send(e) } }
        if (current == null) { terminal(GenerationEvent.Error("No model loaded")); return@channelFlow }
        EngineJob.withJob("llm-generate") {
            var finished = false
            // Collector cancellation must reach the blocked native call: this watcher runs off the engine thread.
            val watcher = launch { try { awaitCancellation() } finally { if (!finished) native.cancel() } }
            var lowMemory = false
            var tooHot = false
            var watchdogJob: Job? = null
            generating = true
            syncService()
            try {
                val l = current ?: throw EngineException("No model loaded")
                requestedDetail = imageDetail
                val gemma4 = l.templateName == "gemma4"
                // Gemma 4 has no assistant-side switch: the hand-rolled formatter enables thinking with "<|think|>" at the start of the system message.
                val assistantPrefix = if (gemma4) null else if (thinkingEnabled) thinking.enablePrefix else thinking.disablePrefix
                val prompt = if (gemma4 && thinkingEnabled) withGemmaThinkFlag(messages) else messages
                val parser = ThinkingParser(thinking.openTag, thinking.closeTag,
                    startInsideThinking = assistantPrefix != null && assistantPrefix == thinking.enablePrefix)
                val nPredict = if (params.maxTokens > 0) params.maxTokens else -1
                watchdogJob = watchdog.launch { lowMemory = true; native.cancel() }

                withContext(engine) {
                    if (this@InferenceEngine.params != params) {
                        this@InferenceEngine.params = params
                        if (ctx != 0L) native.samplerSet(ctx, params.toNativeFloats(), params.toNativeInts(), null)
                    }
                    if (ctx == 0L) { send(GenerationEvent.Resuming(LoadPhase.CONTEXT)); resumeLocked() }
                    // Thread count first, from a fresh headroom sample (a stale one from the previous turn would decide
                    // this whole turn), and before the projector check so mtmd is built with the count the turn runs at.
                    thermal.refreshHeadroom()
                    applyThermalThreadsLocked(l.context.nThreads)
                    if (images.isNotEmpty()) {
                        validatePromptImages(l.model, images, imageDetail)
                        if (!l.model.hasVision) throw EngineException("This model has no vision")
                        if (!l.visionAllowed) throw EngineException(NO_MEMORY_FOR_IMAGES)
                        if (!native.mmprojLoaded(model)) send(GenerationEvent.Resuming(LoadPhase.VISION))
                        ensureVisionLocked(imageDetail)
                    } else if (native.mmprojLoaded(model)) {
                        // A prompt without any image means another chat: the 0.45-1.1 GB projector is not needed.
                        native.mmprojFree(model); mmprojThreads = -1
                    }
                    val live = current!!.copy(visionResident = native.mmprojLoaded(model))
                    current = live
                    _state.value = EngineState.Generating(live, conversationId)
                    thermal.beginGeneration(ctx, perfPreset, ThermalGovernor.targetTps(live))

                    val encoderPeak = if (images.isNotEmpty()) (l.estimate?.encoderPeakBytes ?: 0L) else 0L
                    val avail = cpu.availRamBytes() - encoderPeak
                    val rc = native.generateStart(ctx, prompt.toNative(), images.toNative(), nPredict,
                        assistantPrefix?.toByteArray(Charsets.UTF_8), avail) { done, total, phase ->
                        val encoding = phase == Phase.IMAGE
                        // CRITICAL pauses only the encode itself: native calls back right before each non-cached image
                        // (after a checkpoint, so the abort leaves the KV consistent); cached images and text-only
                        // follow-ups in an image chat never get here and keep working on a hot phone.
                        if (encoding && thermal.pauseImageEncoding()) tooHot = true
                        trySend(GenerationEvent.Prefill(done, total, encoding))
                        !lowMemory && !tooHot
                    }
                    when {
                        rc == 0 -> streamTokens(parser, l, ::terminal) { lowMemory }
                        rc == 1 -> terminal(when {
                            lowMemory -> GenerationEvent.Error(NOT_ENOUGH_MEMORY)
                            tooHot -> GenerationEvent.Error(PHONE_TOO_HOT)
                            else -> GenerationEvent.Done(FinishReason.CANCELLED, statsLocked(l))
                        })
                        rc == 2 -> terminal(GenerationEvent.NeedsTruncation)
                        rc == -4 -> terminal(GenerationEvent.Error("This model produces more image tokens than one batch allows; lower Image detail"))
                        rc == -5 -> terminal(GenerationEvent.Error("Not enough memory to read this image"))
                        rc == -6 -> terminal(GenerationEvent.Error("Vision is not available for this model"))
                        else -> terminal(GenerationEvent.Error(nativeError("Generation failed")))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                terminal(GenerationEvent.Error(if (lowMemory) NOT_ENOUGH_MEMORY else e.message ?: "Generation failed"))
            } catch (e: Throwable) {
                EngineLog.e(TAG, "generate failed", e)
                terminal(GenerationEvent.Error("Generation failed: ${e.message}"))
            } finally {
                finished = true
                watcher.cancel(); watchdogJob?.cancel()
                withContext(NonCancellable + engine) {
                    thermal.endGeneration()
                    if (lowMemory) unloadLocked(NOT_ENOUGH_MEMORY) else {
                        // Free the projector after an image turn when memory is tight.
                        if (images.isNotEmpty() && native.mmprojLoaded(model) && cpu.availRamBytes() < (1L shl 30)) { native.mmprojFree(model); mmprojThreads = -1 }
                        restoreStateLocked()
                    }
                }
                generating = false
                syncService()
            }
        }
    }

    /** Engine thread. Pulls tokens until native reports the end, emitting Thinking/Token per piece and one terminal event. */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<GenerationEvent>.streamTokens(
        parser: ThinkingParser, l: LoadedModel, terminal: suspend (GenerationEvent) -> Unit, lowMemory: () -> Boolean,
    ) {
        var lastNs = System.nanoTime()
        var n = 0
        while (true) {
            val piece = native.generateNext(ctx) ?: break
            val now = System.nanoTime()
            thermal.reportToken(now - lastNs); lastNs = now
            if (piece.isNotEmpty()) emitSplit(parser.feed(Utf8.decode(piece)))
            if (++n % 16 == 0) stepDownThreadsLocked(l.context.nThreads)
        }
        emitSplit(parser.flush())
        val finish = native.generateFinishReason(ctx)
        val stats = statsLocked(l)
        EngineLog.i(TAG, "turn: prompt ${stats.promptTokens} (${stats.reusedTokens} reused) in ${stats.prefillMs} ms, ${stats.generatedTokens} generated in ${stats.decodeMs} ms" +
            (if (stats.imageEncodeMs > 0) ", image ${stats.imageEncodeMs} ms" else "") + (l.gpu?.let { " [gpu]" } ?: " [cpu]"))
        when {
            lowMemory() -> terminal(GenerationEvent.Error(NOT_ENOUGH_MEMORY))
            finish == NFinish.ERROR -> terminal(GenerationEvent.Error(nativeError("Generation failed")))
            else -> terminal(GenerationEvent.Done(FinishReason.fromNative(finish), stats))
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<GenerationEvent>.emitSplit(split: Pair<String, String>) {
        if (split.first.isNotEmpty()) send(GenerationEvent.Thinking(split.first))
        if (split.second.isNotEmpty()) send(GenerationEvent.Token(split.second))
    }

    private fun statsLocked(l: LoadedModel): GenerationStats {
        val v = native.generateStats(ctx)
        fun at(i: Int) = if (i < v.size) v[i] else 0.0
        val s = GenerationStats(
            promptTokens = at(GStat.PROMPT_TOKENS).toInt(), reusedTokens = at(GStat.REUSED_TOKENS).toInt(), generatedTokens = at(GStat.GEN_TOKENS).toInt(),
            prefillMs = at(GStat.PREFILL_MS).toLong(), decodeMs = at(GStat.DECODE_MS).toLong(), imageEncodeMs = at(GStat.IMAGE_ENCODE_MS).toLong(),
            kvUsedTokens = at(GStat.KV_USED_TOKENS).toInt(), nCtx = l.context.nCtx,
        )
        _kvUsed.value = s.kvUsedTokens
        return s
    }

    // ---------------------------------------------------------------------------------------------------------
    // Memory pressure + foreground policy

    /**
     * RUNNING_CRITICAL (or a cached-process COMPLETE/MODERATE trim) while idle => projector + context freed, state
     * Suspended; RUNNING_LOW => projector only; BACKGROUND with keepModelLoaded=false => unload. Nothing is freed
     * while a job runs. Android 14+ only delivers UI_HIDDEN/BACKGROUND, so the app may call [releaseForMemory] from its
     * own availMem poll to get the RUNNING_* behaviour.
     */
    @Suppress("DEPRECATION")   // RUNNING_* levels are still delivered on API 33 (minSdk); 34+ only sends UI_HIDDEN/BACKGROUND
    fun onTrimMemory(level: Int, keepModelLoaded: Boolean) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !keepModelLoaded -> unloadIfIdle()
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> releaseForMemory(critical = true)
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> releaseForMemory(critical = false)
        }
    }

    /**
     * BACKGROUND trim with keepModelLoaded=false: unload only when no job runs. A trim must never cancel a running
     * generation or unload behind a load the user is waiting on (5.2: nothing is freed while a job runs); [unload]
     * keeps its cancel-first behaviour for its explicit callers (model switch, image generation).
     */
    private fun unloadIfIdle() {
        scope.launch {
            if (!EngineJob.tryAcquire("llm-unload")) return@launch
            try {
                withContext(NonCancellable + engine) { unloadLocked() }
                _state.value = EngineState.Idle
            } finally {
                EngineJob.release("llm-unload")
            }
        }
    }

    /** critical: free the context and the projector (Suspended); else the projector only. No-op while a job runs. */
    fun releaseForMemory(critical: Boolean) {
        scope.launch {
            if (!EngineJob.tryAcquire("llm-trim")) return@launch
            try {
                withContext(NonCancellable + engine) {
                    val l = current ?: return@withContext
                    if (native.mmprojLoaded(model)) { native.mmprojFree(model); mmprojThreads = -1 }
                    if (critical && ctx != 0L) { native.contextFree(ctx); ctx = 0L; _kvUsed.value = 0 }
                    EngineLog.i(TAG, "trim(critical=$critical): ctx=${ctx != 0L}")
                    val updated = l.copy(visionResident = false)
                    current = updated
                    _state.value = if (ctx == 0L) EngineState.Suspended(updated, visionReleased = true) else EngineState.Ready(updated)
                }
            } finally {
                EngineJob.release("llm-trim")
            }
        }
    }

    /** EngineService policy: started only when the app goes to the background mid-job, stopped otherwise. */
    fun onAppForeground(foreground: Boolean) {
        this.foreground = foreground
        syncService()
    }

    /**
     * EngineCoordinator (imagegen): an image run is starting / ending. The same keep-alive policy as a text turn
     * applies (12.5: the image job reuses EngineService with [EngineService.HEADING_IMAGE]); the two jobs are
     * mutually exclusive on the native side, so one flag per job and one service is enough.
     */
    override fun onImageJob(active: Boolean, modelName: String) {
        imageJobTitle = if (active) modelName else null
        imageJobActive = active
        syncService()
    }

    /** Foreground service up exactly while (a text turn or an image run is active) AND the app is in the background. */
    @Synchronized
    private fun syncService() {
        val c = context ?: return
        val want = !foreground && (generating || imageJobActive)
        if (want && !serviceStarted) {
            serviceStarted = true
            if (imageJobActive) EngineService.start(c, imageJobTitle ?: "Inferno", EngineService.HEADING_IMAGE)
            else EngineService.start(c, current?.model?.displayName ?: "Inferno")
        } else if (!want && serviceStarted) {
            serviceStarted = false
            EngineService.stop(c)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Engine-thread helpers (callers hold the job and run on `engine`)

    /** Backend up and the GPU policy of the moment applied; every load / estimate entry point goes through here. */
    private fun ensureBackendLocked() {
        val pref = gpuPref
        if (!backendReady) {
            native.backendInit(minLogPriority, cpu.bigMask, pref.ordinal, context?.cacheDir?.resolve("cl-cache")?.path ?: "")
            backendReady = true
            appliedGpuPref = pref
            EngineLog.i(TAG, "backend: ${native.systemInfo()} gpu: ${gpuInfo()}")
        } else if (pref != appliedGpuPref) {
            native.setGpuPolicy(pref.ordinal)
            appliedGpuPref = pref
        }
    }

    private fun estimateLocked(model: LocalModel, config: ContextConfig): MemoryEstimate {
        ensureBackendLocked()
        val v = native.estimateMemory(model.textPath, model.mmprojPath, config.toNative())
        if (v.size < MemEst.SIZE) throw EngineException(nativeError("Could not estimate memory"))
        return MemoryEstimate(
            modelBytes = v[MemEst.MODEL], modelResidentBytes = v[MemEst.MODEL_RESIDENT], modelMappedBytes = v[MemEst.MODEL_MAPPED],
            kvBytes = v[MemEst.KV], computeBytes = v[MemEst.COMPUTE], mmprojBytes = v[MemEst.MMPROJ], clipComputeBytes = v[MemEst.CLIP_COMPUTE],
            encoderPeakBytes = if (!model.hasVision) 0L else model.catalog?.encoderPeakBytes ?: ContextManager.IMPORT_ENCODER_PEAK_BYTES,
            deviceTotal = v[MemEst.DEVICE_TOTAL],
        )
    }

    /** Suspended -> live context (phase "Creating context"); the projector follows lazily via ensureVisionLocked. */
    private fun resumeLocked() {
        val l = current ?: throw EngineException("No model loaded")
        _state.value = EngineState.Loading(l.model, null, LoadPhase.CONTEXT)
        ctx = native.contextCreate(model, l.context.toNative())
        if (ctx == 0L) {
            _state.value = EngineState.Suspended(l, visionReleased = !native.mmprojLoaded(model))
            throw EngineException(nativeError("Could not create the context"))
        }
        appliedThreads = l.context.nThreads
        native.samplerSet(ctx, params.toNativeFloats(), params.toNativeInts(), null)
        _kvUsed.value = 0
        EngineLog.i(TAG, "resumed context nCtx=${l.context.nCtx}")
    }

    /**
     * Projector resident with the current thread count (4.2: mtmd threads are fixed at init, so re-create on change).
     * Residency is keyed on what mmprojLoad actually received (threads, image_max_tokens), not on the ImageDetail
     * enum: BALANCED and HIGH clamp to the same catalog cap for most rows, so switching between them must not
     * rebuild the 0.5-1.1 GB projector. No thermal gate here: the projector is needed for mtmd_tokenize even when
     * every image is already in the KV; the encode itself is refused in generate()'s progress callback.
     */
    private fun ensureVisionLocked(detail: ImageDetail) {
        val l = current ?: throw EngineException("No model loaded")
        val path = l.model.mmprojPath ?: throw EngineException("This model has no vision")
        if (!l.visionAllowed) throw EngineException(NO_MEMORY_FOR_IMAGES)
        val threads = if (appliedThreads > 0) appliedThreads else l.context.nThreads
        val maxTok = imageMaxTokens(l.model, detail)
        if (native.mmprojLoaded(model) && mmprojThreads == threads && mmprojMaxTokens == maxTok) return
        if (native.mmprojLoaded(model)) native.mmprojFree(model)
        _state.value = EngineState.Loading(l.model, null, LoadPhase.VISION)
        val ok = native.mmprojLoad(model, path, threads, imageMinTokens(l.model), maxTok) { done, total, _ ->
            _state.value = EngineState.Loading(l.model, if (total > 0) done.toFloat() / total else null, LoadPhase.VISION); true
        }
        if (!ok) { mmprojThreads = -1; mmprojMaxTokens = -1; throw EngineException(nativeError("Could not load the vision projector")) }
        mmprojThreads = threads; mmprojMaxTokens = maxTok
        updateCurrent(l.copy(visionResident = true))
    }

    private fun applyThermalThreadsLocked(requested: Int) {
        val want = thermal.threadsFor(requested)
        if (want == appliedThreads || ctx == 0L) return
        val cfg = current?.context ?: return
        if (native.contextSetThreads(ctx, want, want, cfg.bigCoresOnly, cfg.poll)) {
            EngineLog.i(TAG, "threads $appliedThreads -> $want (thermal status ${thermal.status.value})")
            appliedThreads = want
        }
    }

    /** Mid-turn step-down only (lower count => cheap llama_set_n_threads path); stepping back up waits for the next turn. */
    private fun stepDownThreadsLocked(requested: Int) {
        val want = thermal.threadsFor(requested)
        if (want < appliedThreads) applyThermalThreadsLocked(requested)
    }

    private fun restoreStateLocked() {
        val l = current ?: run { _state.value = EngineState.Idle; return }
        val updated = l.copy(visionResident = native.mmprojLoaded(model))
        current = updated
        _state.value = if (ctx != 0L) EngineState.Ready(updated) else EngineState.Suspended(updated, visionReleased = !updated.visionResident)
    }

    private fun unloadLocked(error: String? = null, clearEstimates: Boolean = true) {
        if (ctx != 0L) { native.contextFree(ctx); ctx = 0L }
        if (model != 0L) { native.modelFree(model); model = 0L }
        if (clearEstimates && backendReady) native.estimateCacheClear()
        val prev = current
        current = null
        appliedThreads = 0; mmprojThreads = -1; mmprojMaxTokens = -1; requestedDetail = ImageDetail.BALANCED
        _kvUsed.value = 0
        if (error != null) _state.value = EngineState.Error(error, prev?.model)
    }

    private fun updateCurrent(l: LoadedModel) {
        current = l
        _state.value = when (val s = _state.value) {
            is EngineState.Ready -> EngineState.Ready(l)
            is EngineState.Generating -> s.copy(loaded = l)
            is EngineState.Suspended -> s.copy(loaded = l)
            else -> s
        }
    }

    private fun nativeError(fallback: String): String = native.lastErrorString().ifBlank { fallback }

    companion object {
        private const val TAG = "Engine"
        private const val NOT_ENOUGH_MEMORY = "Not enough memory"
        private const val NO_MEMORY_FOR_IMAGES = "Not enough memory for images with this model"
        private const val PHONE_TOO_HOT = "Phone is too hot to read images right now"

        /** "load_tensors:  CPU_KLEIDIAI model buffer size =  1103.12 MiB" lines -> "CPU_KLEIDIAI 1.1 GB · ...". */
        internal fun parseBufferTypes(lines: String): String = lines.lineSequence().mapNotNull { line ->
            val m = Regex("""(\S+) model buffer size\s*=\s*([0-9.]+) MiB""").find(line) ?: return@mapNotNull null
            val mib = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val size = if (mib >= 1024) String.format(java.util.Locale.US, "%.1f GB", mib / 1024) else "${mib.toInt()} MB"
            "${m.groupValues[1]} $size"
        }.joinToString(" · ")

        fun imageMinTokens(model: LocalModel): Int = ContextManager.imageMinTokens(model)
        fun imageMaxTokens(model: LocalModel, detail: ImageDetail): Int = ContextManager.imageMaxTokens(model, detail)
        fun imageEdgeCap(model: LocalModel, detail: ImageDetail): Int = ContextManager.imageEdgeCap(model, detail)
    }
}
