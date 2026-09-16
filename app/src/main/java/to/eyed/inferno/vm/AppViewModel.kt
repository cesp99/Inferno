package to.eyed.inferno.vm

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.inferno.AppContainer
import to.eyed.inferno.data.DevFlag
import to.eyed.inferno.data.ContextPolicy
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.data.PerfPreset
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.data.devBenchmark
import to.eyed.inferno.engine.Calibration
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.CpuTopology
import to.eyed.inferno.engine.BudgetGateException
import to.eyed.inferno.engine.EngineException
import to.eyed.inferno.engine.EngineService
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.loadedAny
import to.eyed.inferno.engine.residentFootprintBytes
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.KvCacheType
import to.eyed.inferno.engine.MemoryEstimate
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ModelCatalog
import to.eyed.inferno.models.ModelEntry
import to.eyed.inferno.models.ModelRepository
import to.eyed.inferno.models.StorageInfo
import to.eyed.inferno.ui.S

enum class Screen { CHAT, MODELS, SETTINGS, BENCH, CREATE, GALLERY }

sealed interface PendingAction {
    data class Share(val uris: List<Uri>) : PendingAction
    data object Stop : PendingAction
}

/**
 * Screen, engine lifecycle, model selection and settings (spec 5.5). Activity-scoped; `screen` lives in the
 * SavedStateHandle so a process death restores the same screen.
 */
class AppViewModel(private val c: AppContainer, private val handle: SavedStateHandle) : ViewModel() {
    val settings: StateFlow<SettingsState> = c.prefs.settings
    val engine: StateFlow<EngineState> = c.engine.state
    val models: StateFlow<List<ModelEntry>> = c.models.entries
    val imageDownloads: StateFlow<Map<String, DownloadState>> = c.models.imageDownloads
    val storage: StateFlow<StorageInfo> = c.models.storage
    /** Model manager entry: files come and go outside the app (file managers, storage cleaners); the list is rescanned on open. */
    fun rescanModels() = c.models.refresh()
    val meteredConfirm: StateFlow<ModelRepository.MeteredRequest?> = c.models.meteredConfirm

    /** BENCH exists only in developer mode: a stale saved screen (or a flag flipped off) lands on CHAT instead. */
    val screen: StateFlow<Screen> = combine(handle.getStateFlow(KEY_SCREEN, Screen.CHAT.name), settings) { name, s ->
        val wanted = runCatching { Screen.valueOf(name) }.getOrDefault(Screen.CHAT)
        if (wanted == Screen.BENCH && !s.devBenchmark) Screen.CHAT else wanted
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.CHAT)

    /** No onboarding yet and nothing on disk; false until prefs are read so the splash never flashes FirstRun. */
    val showFirstRun: StateFlow<Boolean> = combine(c.prefs.loaded, settings, models) { loaded, s, m ->
        loaded && !s.onboardingDone && m.none { it.isDownloaded }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _loadPlan = MutableStateFlow<ContextManager.Plan?>(null)
    val loadPlan: StateFlow<ContextManager.Plan?> = _loadPlan.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }
    private var noticeJob: Job? = null
    /** Shown top-centre until dismissed or [NOTICE_MS] pass: a stale "8 oldest messages did not fit" must not follow the user into the next chat. */
    fun notice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(NOTICE_MS); if (_notice.value == text) _notice.value = null }
    }

    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pendingIntent: StateFlow<PendingAction?> = _pending.asStateFlow()

    /** "Phone is hot" chip: the governor is currently taking threads away (12.3 c). */
    val isHot: StateFlow<Boolean> = combine(c.thermal.status, c.thermal.headroom) { _, _ -> c.thermal.isHot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Set by Root when the chat screen is first displayed: the keepModelLoaded auto-load waits for it (5.5). */
    private val chatShown = MutableStateFlow(false)
    fun onChatDisplayed() { chatShown.value = true }

    private var loadJob: Job? = null
    /** Set after a successful load until ChatViewModel starts the first turn (crash-loop guard, 5.5). */
    @Volatile private var firstTurnPending = false
    private var deferredThreads = false
    private var deferredReload = false
    /** useMmap the resident weights were loaded with: a change needs a full reload, not a reconfigure. */
    private var loadedUseMmap = true

    init {
        viewModelScope.launch { c.models.notice.collect { notice(it) } }
        viewModelScope.launch {
            settings.map { it.perfPreset to it.minLogPriority }.distinctUntilChanged().collect { (p, log) ->
                c.engine.perfPreset = p; c.engine.minLogPriority = log
            }
        }
        // Settings that needed a reload while a turn was running are applied at the next Ready.
        viewModelScope.launch {
            engine.collect { s ->
                if (s is EngineState.Ready) {
                    if (deferredReload) { deferredReload = false; reloadCurrent() }
                    else if (deferredThreads) { deferredThreads = false; applyThreads() }
                }
            }
        }
        viewModelScope.launch { startupPolicy() }
        viewModelScope.launch { firstRunAutoLoad() }
    }

    /** Crash-loop guard first; otherwise the keepModelLoaded auto-load on the first CHAT display. */
    private suspend fun startupPolicy() {
        c.prefs.loaded.first { it }
        val s = settings.value
        val crashed = s.loadAttemptModelId
        if (crashed != null) {
            c.prefs.setLastLoadCrash(crashed)
            c.prefs.setLoadAttempt(null)
            notice(S.crashedWhileLoading(c.models.displayName(crashed)))
            return
        }
        val selected = s.selectedModelId ?: return
        if (!s.keepModelLoaded) return
        chatShown.first { it }
        models.first { it.isNotEmpty() }          // the disk scan has landed
        val local = c.models.local(selected) ?: return
        if (engine.value is EngineState.Idle) selectAndLoad(local.id)
    }

    /** First run: the recommended model finishing its download completes onboarding and loads it (6.1 flow 2). */
    private suspend fun firstRunAutoLoad() {
        c.prefs.loaded.first { it }
        val rec = ModelCatalog.recommended
        models.map { list -> list.firstOrNull { it.id == rec.id }?.isDownloaded == true }.distinctUntilChanged().collect { downloaded ->
            if (downloaded && !settings.value.onboardingDone) {
                finishOnboarding()
                if (engine.value is EngineState.Idle) selectAndLoad(rec.id)
            }
        }
    }

    /**
     * [showFirstRun] once prefs and the disk scan are in; suspends until then so a share-to-Inferno on a cold start
     * (enqueued before the first composition, while showFirstRun is still false) cannot slip past the FirstRun gate.
     */
    suspend fun firstRunAfterPrefs(): Boolean {
        c.prefs.loaded.first { it }
        models.first { it.isNotEmpty() }
        return !settings.value.onboardingDone && models.value.none { it.isDownloaded }
    }

    // ---- navigation ------------------------------------------------------------------------------------------

    fun navigate(s: Screen) {
        if (s == Screen.BENCH && !settings.value.devBenchmark) return   // the entry points are hidden; this backs them up
        handle[KEY_SCREEN] = s.name
    }

    /** false when already on CHAT (the system then handles back). GALLERY returns to CREATE, everything else to CHAT. */
    fun back(): Boolean = when (screen.value) {
        Screen.CHAT -> false
        Screen.GALLERY -> { navigate(Screen.CREATE); true }
        else -> { navigate(Screen.CHAT); true }
    }

    // ---- intents ---------------------------------------------------------------------------------------------

    /**
     * Maps ACTION_SEND (image mime) and the notification Stop extra, then strips the intent so a config change never
     * replays it. The strip only covers the in-process Intent: after a process death the system re-delivers the
     * original one, so MainActivity only enqueues in onCreate when savedInstanceState is null.
     */
    fun enqueue(intent: Intent?) {
        intent ?: return
        val action: PendingAction? = when {
            intent.getStringExtra(EngineService.EXTRA_ACTION) == EngineService.ACTION_STOP -> PendingAction.Stop
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { PendingAction.Share(listOf(it)) }
            intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.take(2)?.takeIf { it.isNotEmpty() }?.let { PendingAction.Share(it) }
            else -> null
        }
        intent.removeExtra(EngineService.EXTRA_ACTION)
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.action = null
        // Never overwrite an action Root has not consumed yet.
        if (action != null && _pending.value == null) _pending.value = action
    }

    fun consumePending() { _pending.value = null }

    // ---- model lifecycle -------------------------------------------------------------------------------------

    /** Plan + load (+ calibration bench on the very first load of a model). Sets selectedModelId and the crash guard. */
    fun selectAndLoad(modelId: String) {
        val local = c.models.local(modelId) ?: run { notice(S.notDownloaded(c.models.displayName(modelId))); return }
        // Join the previous load before arming the new guard, so its cancellation clear cannot land after our set.
        val previous = loadJob
        _loadInProgress.value = true
        loadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _loadInProgress.value = true
            try { loadNow(local) } finally { _loadInProgress.value = false }
        }
    }

    private val _loadInProgress = MutableStateFlow(false)
    /** True from selectAndLoad() until its plan/load/calibration finished or failed (the engine is still Idle while planning). */
    val loadInProgress: StateFlow<Boolean> = _loadInProgress.asStateFlow()

    fun unload() {
        loadJob?.cancel()
        viewModelScope.launch { runCatching { c.engine.unload() }.onFailure { notice(it.message ?: S.couldNotUnload) } }
    }

    /** Context page Apply: 0 = Auto. Persists, then rebuilds the context (deferred while generating). */
    fun reloadWithContext(nCtx: Int) {
        viewModelScope.launch { c.prefs.setContextSize(nCtx); reloadOrDefer() }
    }

    /** Context page Apply with a KV type change in the same step: both prefs land before the single reconfigure. */
    fun applyContext(nCtx: Int, kv: KvCachePref) {
        viewModelScope.launch { c.prefs.setContextSize(nCtx); c.prefs.setKvCache(kv); reloadOrDefer() }
    }

    private suspend fun loadNow(local: LocalModel) {
        val s = settings.value
        firstTurnPending = false
        c.prefs.setSelectedModel(local.id)
        if (!s.onboardingDone) c.prefs.setOnboardingDone(true)
        // Guard is written BEFORE the native load: a kernel-level death leaves it set for the next start (5.5).
        c.prefs.setLoadAttempt(local.id)
        try {
            // The image engine goes first so the plan's availMem reading (and the budget gate) see the real headroom;
            // InferenceEngine.beforeLoad repeats this, harmlessly, right before the weights load.
            c.imageGen.unload()
            var plan = c.contextManager.plan(local, s, c.cpu, reclaimableBytes())
            _loadPlan.value = plan
            if (plan.reason != null) throw EngineException(plan.reason)
            val loaded = try {
                c.engine.load(local, plan.config, s.imageDetail, s.useMmap, plan.visionAllowed)
            } catch (e: BudgetGateException) {
                // Free RAM moved between the plan and the load (typical right after a download): plan once more
                // against the memory of now instead of failing the first start with a stale number.
                plan = c.contextManager.plan(local, s, c.cpu, reclaimableBytes())
                _loadPlan.value = plan
                if (plan.reason != null) throw EngineException(plan.reason)
                c.engine.load(local, plan.config, s.imageDetail, s.useMmap, plan.visionAllowed)
            }
            loadedUseMmap = s.useMmap
            c.engine.setSampling(effectiveParams())
            val cal = s.calibration[local.id]
            if (cal == null) calibrate(local.id, loaded.loadMs) else c.engine.setCalibration(cal.copy(loadMs = loaded.loadMs))
            c.prefs.setLoadAttempt(null)
            firstTurnPending = true
            when {
                plan.clampedFrom != null -> notice(S.contextReducedTo(ContextManager.tokens(plan.config.nCtx)))
                local.hasVision && !plan.visionAllowed -> notice(S.noMemoryForVision)
            }
        } catch (e: CancellationException) {
            // A user-cancelled load (Unload, another model picked) is not a crash: disarm the guard.
            withContext(NonCancellable) { c.prefs.setLoadAttempt(null) }
            throw e
        } catch (e: Exception) {
            c.prefs.setLoadAttempt(null)
            notice(e.message ?: S.couldNotLoadModel)
        }
    }

    /** pp512/tg128 x1 on the first load of a model; the result drives the ADPF target and the meta-line estimate. */
    private suspend fun calibrate(modelId: String, loadMs: Long) {
        val r = c.engine.bench(nPrompt = 512, nGen = 128, reps = 1) { _, _ -> }
        val cal = Calibration(ppTps = r.ppTps, tgTps = r.tgTps, loadMs = loadMs, at = System.currentTimeMillis())
        c.prefs.setCalibration(modelId, cal)
        c.engine.setCalibration(cal)
    }

    /** Re-plan and recreate the context for the loaded model (kv / ctx / fa / mmap changes). */
    private suspend fun reloadCurrent() {
        val model = engine.value.modelOrNull ?: return
        val local = c.models.local(model.id) ?: return
        val s = settings.value
        try {
            // Same as loadNow: the image engine's weights go first so the plan sees the real headroom.
            c.imageGen.unload()
            val plan = c.contextManager.plan(local, s, c.cpu, reclaimableBytes())
            _loadPlan.value = plan
            if (plan.reason != null) throw EngineException(plan.reason)
            val current = c.engine.loaded?.context
            // mmap / DIRECT_IO is a load-time choice; everything else only needs a new context.
            if (current != null && s.useMmap == loadedUseMmap) c.engine.reconfigure(plan.config) else loadNow(local)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice(e.message ?: S.couldNotApplySetting)
        }
    }
    private suspend fun reloadOrDefer() {
        when (engine.value) {
            is EngineState.Generating, is EngineState.Loading -> { deferredReload = true; notice(S.appliedAfterReload) }
            is EngineState.Idle, is EngineState.Error -> Unit
            else -> reloadCurrent()
        }
    }

    private suspend fun applyThreads() {
        val s = settings.value
        when (engine.value) {
            is EngineState.Generating, is EngineState.Loading -> deferredThreads = true
            is EngineState.Idle, is EngineState.Error -> Unit
            else -> runCatching { c.engine.setThreads(s.threads, s.pinBigCores, s.poll) }.onFailure { notice(it.message ?: S.couldNotChangeThreads) }
        }
    }

    /** Cheap analytic estimate for the live context slider (no native call): KV scaled from the catalog per-token figure. */
    fun previewEstimate(modelId: String, nCtx: Int, kv: KvCachePref): MemoryEstimate {
        val local = c.models.local(modelId)
        val catalog = local?.catalog ?: ModelCatalog.byId(modelId)
        val perToken = catalog?.kvBytesPerTokenF16?.toLong() ?: (64L * 1024)
        val budget = budgetBytes()
        val kvType = ContextManager.kvTypeFor(kv, perToken * nCtx, budget)
        val perElement = when (kvType) { KvCacheType.F16 -> 1.0; KvCacheType.Q8_0 -> 34.0 / 64; KvCacheType.Q4_0 -> 18.0 / 64 }
        val last = _loadPlan.value?.estimate?.takeIf { _loadPlan.value?.config != null && (engine.value.modelOrNull?.id == modelId) }
        return MemoryEstimate(
            modelBytes = last?.modelBytes ?: (local?.textBytes ?: catalog?.text?.sizeBytes ?: 0L),
            modelResidentBytes = last?.modelResidentBytes ?: (local?.textBytes ?: catalog?.text?.sizeBytes ?: 0L),
            modelMappedBytes = last?.modelMappedBytes ?: 0L,
            kvBytes = (perToken * nCtx * perElement).toLong(),
            computeBytes = last?.computeBytes ?: (256L shl 20),
            mmprojBytes = last?.mmprojBytes ?: (local?.mmprojBytes ?: catalog?.mmproj?.sizeBytes ?: 0L),
            clipComputeBytes = last?.clipComputeBytes ?: 0L,
            encoderPeakBytes = last?.encoderPeakBytes ?: (catalog?.encoderPeakBytes ?: 0L),
            deviceTotal = c.cpu.totalRamBytes,
            approximate = true,
        )
    }

    // ---- read-only facts for the settings / models / bench UI (WP8, additive) ---------------------------------

    /** CPU topology and RAM facts ("4 big cores detected", the Compute info row, UnsupportedCpuScreen features). */
    val cpu: CpuTopology get() = c.cpu
    /** The planner's memory budget right now (availMem plus what the loaded model would give back, minus the LMK margin); the context page bar is estimate / budget. */
    fun budgetBytes(): Long = ContextManager.budgetBytes(c.cpu.availRamBytes() + reclaimableBytes())
    /** RAM the resident LLM frees before any new plan is loaded (weights are unloaded ahead of every load / reconfigure). */
    private fun reclaimableBytes(): Long = engine.value.loadedAny?.residentFootprintBytes ?: 0L
    /** Native ggml system-info line for Settings > Advanced > System info (binds the JNI lazily; supported CPUs only). */
    fun systemInfo(): String = runCatching { c.engine.systemInfo() }.getOrElse { it.message ?: S.systemInfoUnavailable }
    /** POST_NOTIFICATIONS granted and the app not muted: drives the "Enable notifications" notice in the model manager. */
    val notificationsEnabled: Boolean get() = c.notifications.enabled
    /** Clears the camera capture cache (Settings > Storage > Clear image cache). */
    fun clearImageCache() { viewModelScope.launch { runCatching { c.images.clearCache() }; notice(S.imageCacheCleared) } }

    // ---- downloads / storage ---------------------------------------------------------------------------------

    fun download(modelId: String, allowMetered: Boolean = false) = c.models.startDownload(modelId, allowMetered)
    fun confirmMetered(modelId: String, always: Boolean) = c.models.confirmMetered(modelId, always)
    fun waitForWifi(modelId: String) = c.models.waitForWifi(modelId)
    fun cancelDownload(modelId: String) = c.models.cancelDownload(modelId)
    fun discardPartial(modelId: String) = c.models.discardPartial(modelId)
    fun deleteModel(modelId: String) {
        viewModelScope.launch { runCatching { c.models.delete(modelId) }.onFailure { notice(it.message ?: S.couldNotDelete) } }
    }
    fun deleteAllModels() {
        viewModelScope.launch { runCatching { c.models.deleteAll() }.onFailure { notice(it.message ?: S.couldNotDelete) } }
    }
    fun importModel(uri: Uri, displayName: String?, isMmproj: Boolean, pairWith: String?) {
        viewModelScope.launch {
            runCatching { c.models.import(uri, displayName, isMmproj, pairWith) }
                .onSuccess { notice(S.importedModel(it.displayName)) }
                .onFailure { notice(it.message ?: S.importFailed) }
        }
    }
    fun finishOnboarding() { viewModelScope.launch { c.prefs.setOnboardingDone(true) } }
    fun markNotificationsAsked() { viewModelScope.launch { c.prefs.setNotificationsAsked(true) } }

    // ---- settings passthroughs -------------------------------------------------------------------------------

    fun setPerfPreset(p: PerfPreset) = viewModelScope.launch { c.prefs.setPerfPreset(p); applyThreads() }
    fun setThreads(n: Int) = viewModelScope.launch { c.prefs.setThreads(n); applyThreads() }
    fun setPinBigCores(v: Boolean) = viewModelScope.launch { c.prefs.setPinBigCores(v); applyThreads() }
    fun setPoll(v: Int) = viewModelScope.launch { c.prefs.setPoll(v); applyThreads() }
    fun setKvCache(k: KvCachePref) = viewModelScope.launch { c.prefs.setKvCache(k); reloadOrDefer() }
    fun setFlashAttention(v: Boolean) = viewModelScope.launch { c.prefs.setFlashAttention(v); reloadOrDefer() }
    fun setUseMmap(v: Boolean) = viewModelScope.launch { c.prefs.setUseMmap(v); reloadOrDefer() }
    fun setKeepModelLoaded(v: Boolean) = viewModelScope.launch { c.prefs.setKeepModelLoaded(v) }
    fun setParams(p: GenerationParams) = viewModelScope.launch { c.prefs.setParams(p); applySampling() }
    fun setUseModelDefaults(v: Boolean) = viewModelScope.launch { c.prefs.setUseModelDefaults(v); applySampling() }
    fun setSystemPrompt(s: String) = viewModelScope.launch { c.prefs.setSystemPrompt(s) }
    fun setThinking(v: Boolean) = viewModelScope.launch { c.prefs.setThinking(v) }
    fun setImageDetail(d: ImageDetail) = viewModelScope.launch { c.prefs.setImageDetail(d) }
    fun setContextPolicy(p: ContextPolicy) = viewModelScope.launch { c.prefs.setContextPolicy(p) }
    fun setStreamingAnimations(v: Boolean) = viewModelScope.launch { c.prefs.setStreamingAnimations(v) }
    fun setHaptics(v: Boolean) = viewModelScope.launch { c.prefs.setHaptics(v) }
    fun setAllowMeteredDownloads(v: Boolean) = viewModelScope.launch { c.prefs.setAllowMeteredDownloads(v) }
    fun setMinLogPriority(p: Int) = viewModelScope.launch { c.prefs.setMinLogPriority(p) }
    fun setDeveloperMode(v: Boolean) = viewModelScope.launch { c.prefs.setDeveloperMode(v) }
    fun setDevFlag(flag: DevFlag, v: Boolean) = viewModelScope.launch { c.prefs.setDevFlag(flag, v) }

    // ---- developer-mode facts (ui/settings/DeveloperPage.kt, chat thermal line) ----------------------------------

    /** PowerManager.THERMAL_STATUS_* as the governor sees it. */
    val thermalStatus: StateFlow<Int> = c.thermal.status
    /** True while a PerfPreset.MAX generation holds sustained performance mode (MainActivity applies it to the window). */
    val sustainedMode: StateFlow<Boolean> = c.thermal.sustainedRequested
    /** Thread count the live context runs with right now (thermal-adjusted); 0 without a context. */
    fun activeThreads(): Int = c.engine.activeThreads
    /** Observable twin of [activeThreads] for rows that must repaint when the native thread change lands. */
    val activeThreadsFlow: StateFlow<Int> get() = c.engine.activeThreadsFlow
    /** "CPU_KLEIDIAI 1.1 GB · CPU_Mapped 0.3 GB" from the last load's llama.cpp log, "" before any load. */
    fun modelBufferTypes(): String = runCatching { c.engine.modelBufferTypes() }.getOrDefault("")
    /** "4 threads · pinned · thermal light · sustained off": the chat empty-state / chip long-press line (showThermalInfo). */
    fun computeLine(live: Int = activeThreads()): String {
        val s = settings.value
        val live = live.takeIf { it > 0 }
        val threads = if (live != null && live != s.threads) "$live of ${s.threads} threads" else "${s.threads} threads"
        val thermal = thermalName(thermalStatus.value)
        return "$threads · ${if (s.pinBigCores) "pinned" else "free"} · thermal $thermal · sustained ${if (sustainedMode.value) "on" else "off"}"
    }

    private suspend fun applySampling() {
        if (engine.value !is EngineState.Idle && engine.value !is EngineState.Generating) runCatching { c.engine.setSampling(effectiveParams()) }
    }

    /** Model defaults (catalog SamplingDefaults) when useModelSamplingDefaults, else the user's params. */
    fun effectiveParams(): GenerationParams {
        val s = settings.value
        if (!s.useModelSamplingDefaults) return s.params
        val d = engine.value.modelOrNull?.catalog?.sampling ?: return s.params
        return s.params.copy(temperature = d.temperature, topP = d.topP, topK = d.topK, minP = d.minP,
            repeatPenalty = d.repeatPenalty, presencePenalty = d.presencePenalty)
    }

    // ---- crash-loop guard hand-off with ChatViewModel --------------------------------------------------------

    /** Before the first generate() after a load: re-arm the guard so a native abort mid-turn is caught on restart. */
    suspend fun beginFirstTurn() {
        if (!firstTurnPending) return
        firstTurnPending = false
        engine.value.modelOrNull?.id?.let { c.prefs.setLoadAttempt(it) }
    }

    /**
     * The first turn after a load ended in Kotlin (Done, Error, Stop): the model is known-good on this phone. Not
     * while another load is in flight, because then the armed guard belongs to that load.
     */
    fun markFirstTurnOk() {
        viewModelScope.launch { if (settings.value.loadAttemptModelId != null && !_loadInProgress.value) c.prefs.setLoadAttempt(null) }
    }

    // ---- additive UI helpers (WP7) ---------------------------------------------------------------------------

    /** Sidebar footer line, e.g. "Dimensity 7300 · 4 big cores · 7.7 GB". Static facts, computed once. */
    val deviceSummary: String by lazy {
        val gb = String.format(java.util.Locale.US, "%.1f GB", c.cpu.totalRamBytes / 1e9)
        val cores = if (c.cpu.nBig > 0) "${c.cpu.nBig} big cores" else "${c.cpu.nCores} cores"
        listOf(c.cpu.socName.takeIf { it.isNotBlank() } ?: S.cpuFallbackName, cores, gb).joinToString(" · ")
    }

    /** Display name for a model id that may no longer be on disk (TurnDetailsSheet for old turns). */
    fun modelDisplayName(modelId: String): String = c.models.displayName(modelId)

    companion object {
        private const val NOTICE_MS = 8_000L
        private const val KEY_SCREEN = "screen"
        private val THERMAL_NAMES = listOf("nominal", "light", "moderate", "severe", "critical", "emergency", "shutdown")
        fun thermalName(status: Int): String = THERMAL_NAMES.getOrElse(status) { status.toString() }
    }
}
