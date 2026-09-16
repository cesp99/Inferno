package to.eyed.inferno.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import to.eyed.inferno.engine.Calibration
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.imagegen.EtaStore
import to.eyed.inferno.models.DownloadPrefs
import to.eyed.inferno.models.ImageDetail
import java.io.IOException

enum class KvCachePref { AUTO, F16, Q8_0, Q4_0 }
/**
 * What happens when a chat outgrows the model's context (Settings > Chat). ROLLING drops the oldest turns from the
 * prompt (the history stays), COMPACT asks the model to summarize the older turns before the next one, STOP blocks the
 * composer until the user opens a new chat. Absorbs the old `autoTrim` boolean (false -> STOP, true -> ROLLING).
 */
enum class ContextPolicy { ROLLING, COMPACT, STOP }
/**
 * Who the settings are for (Settings > Experience). NORMAL sees a consumer app: chat, storage, about. POWER adds
 * context, prompts and generation controls. DEVELOPER adds the engine knobs, timings and the Developer page. The
 * order matters: `experienceLevel >= POWER` is how surfaces gate themselves (data/DevFlags.kt).
 */
enum class ExperienceLevel { NORMAL, POWER, DEVELOPER }
/** Presets shown in Settings > Inference > Performance; Advanced exposes the underlying knobs. */
enum class PerfPreset(val label: String, val threads: Int, val pin: Boolean, val poll: Int, val sustained: Boolean) {
    AUTO("Auto", 4, true, 50, false), MAX("Max", 4, true, 100, true), COOL("Cool", 3, true, 0, false)
}

data class SettingsState(
    // inference
    val perfPreset: PerfPreset = PerfPreset.AUTO,
    val threads: Int = 4,                       // 1..8; default = number of big cores (Advanced; preset writes it)
    val pinBigCores: Boolean = true,            // Advanced
    val contextSize: Int = 0,                   // 0 = Auto (max): planner searches UP to the largest size that fits; else tokens, multiple of 1024
    val kvCache: KvCachePref = KvCachePref.AUTO,
    val flashAttention: Boolean = true,         // Advanced; setKvCache(Q8_0/Q4_0) forces true and the toggle is disabled ("Required for quantized KV")
    val useMmap: Boolean = true,                // Advanced; "false" selects LLAMA_LOAD_MODE_DIRECT_IO for repacked tensors
    val poll: Int = 50,                         // Advanced (preset writes it)
    val keepModelLoaded: Boolean = true,        // keep weights in RAM while backgrounded
    // generation
    val params: GenerationParams = GenerationParams(),
    val useModelSamplingDefaults: Boolean = true,
    val systemPrompt: String = "",
    val thinking: Boolean = false,
    val imageDetail: ImageDetail = ImageDetail.BALANCED,
    // chat
    val streamingAnimations: Boolean = true,
    val haptics: Boolean = true,
    // downloads
    val allowMeteredDownloads: Boolean = false, // Settings > Storage; when false a metered network requires a per-download confirm
    val notificationsAsked: Boolean = false,    // POST_NOTIFICATIONS was requested once (5.6 Root.kt); never asked again
    // app
    val selectedModelId: String? = null,
    val onboardingDone: Boolean = false,
    val minLogPriority: Int = 4,                // android.util.Log.INFO
    // crash-loop guard (5.5 AppViewModel init): set before engine.load()/first generate(), cleared on Ready / first Done
    val loadAttemptModelId: String? = null,
    val lastLoadCrashModelId: String? = null,
    /** JSON map modelId -> Calibration (engine/EngineTypes.kt); measured pp/tg/load/image-encode per model on this phone. */
    val calibration: Map<String, Calibration> = emptyMap(),
    // ---- image generation ----
    /** Image model chosen on the Create screen (ImageModelSpec.id). */
    val selectedImageModelId: String? = null,
    /** Learned seconds per denoise step keyed EtaModel.key(modelId, px) = "<imageModelId>:<px>"; the EtaStore view of this map feeds ImageGenRepository's ETA. */
    val imageGenSecPerStep: Map<String, Float> = emptyMap(),
    // ---- Experience level (ui/settings/SettingsSheet.kt Experience card) and the developer sub-toggles ----
    // NORMAL by default so a first-time user never sees tok/s, rings, cores or KV types; every show* flag below counts
    // only at DEVELOPER (see data/DevFlags.kt for the combined readers). The native log floor is minLogPriority.
    val experienceLevel: ExperienceLevel = ExperienceLevel.NORMAL,
    val showGenerationStats: Boolean = true,    // meta line under the latest answer (tok/s, tokens, prefill, ctx, image encode)
    val showContextMeter: Boolean = true,       // ContextRing in the model chip
    val showTurnDetails: Boolean = true,        // Details action + TurnDetailsSheet
    val showBenchmark: Boolean = true,          // Benchmark entry (sidebar, Advanced) + Screen.BENCH
    val showModelTechSpecs: Boolean = true,     // quant · ctx · tok/s · KV on model rows (else size · tier · blurb · licence)
    val showThermalInfo: Boolean = true,        // threads / thermal line in the chat empty state, chip long-press, Settings Compute row
    val showTokenCounter: Boolean = true,       // live token count + tok/s while streaming
    // ---- Context policy (infinite chats): replaces `autoTrim`; the old key is still read for the migration ----
    val contextPolicy: ContextPolicy = ContextPolicy.ROLLING,
) {
    /** The old master switch, kept as a derived fact so every developer gate reads the same: DEVELOPER and nothing else. */
    val developerMode: Boolean get() = experienceLevel == ExperienceLevel.DEVELOPER
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Every SettingsState field is one preference key (enums by name, GenerationParams / calibration as JSON). Unknown
 * or corrupt values fall back to the field default so a schema change never crashes an existing install.
 * [store] is injectable so tests can use a throw-away file instead of the process-wide "settings" store.
 * Also the [EtaStore] of the image-generation ETA model: the learned s/step map is just another field; and
 * the [DownloadPrefs] seam of `models/ModelRepository` (metered-network consent + selected model).
 */
class AppPrefs(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val store: DataStore<Preferences> = context.applicationContext.settingsStore,
) : EtaStore, DownloadPrefs {
    private val _loaded = MutableStateFlow(false)
    /** False until the first value has been read from disk (splash keep-on condition, 5.1). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val settings: StateFlow<SettingsState> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toState() }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, SettingsState())

    suspend fun update(transform: (SettingsState) -> SettingsState) {
        store.edit { prefs -> prefs.write(transform(prefs.toState())) }
    }

    // ---- convenience setters ----
    suspend fun setPerfPreset(p: PerfPreset) = update { it.copy(perfPreset = p, threads = p.threads, pinBigCores = p.pin, poll = p.poll) }
    suspend fun setThreads(n: Int) = update { it.copy(threads = n.coerceIn(1, 8)) }
    suspend fun setPinBigCores(v: Boolean) = update { it.copy(pinBigCores = v) }
    suspend fun setContextSize(tokens: Int) = update { it.copy(contextSize = tokens.coerceAtLeast(0)) }
    /** A quantized V cache needs flash attention (ContextConfig invariant), so Q8_0/Q4_0 force the toggle on. */
    suspend fun setKvCache(k: KvCachePref) = update { it.copy(kvCache = k, flashAttention = it.flashAttention || k == KvCachePref.Q8_0 || k == KvCachePref.Q4_0) }
    suspend fun setFlashAttention(v: Boolean) = update { it.copy(flashAttention = v || it.kvCache == KvCachePref.Q8_0 || it.kvCache == KvCachePref.Q4_0) }
    suspend fun setUseMmap(v: Boolean) = update { it.copy(useMmap = v) }
    suspend fun setPoll(v: Int) = update { it.copy(poll = v.coerceIn(0, 100)) }
    suspend fun setKeepModelLoaded(v: Boolean) = update { it.copy(keepModelLoaded = v) }
    suspend fun setParams(p: GenerationParams) = update { it.copy(params = p) }
    suspend fun setUseModelDefaults(v: Boolean) = update { it.copy(useModelSamplingDefaults = v) }
    suspend fun setSystemPrompt(s: String) = update { it.copy(systemPrompt = s) }
    suspend fun setThinking(v: Boolean) = update { it.copy(thinking = v) }
    suspend fun setImageDetail(d: ImageDetail) = update { it.copy(imageDetail = d) }
    suspend fun setStreamingAnimations(v: Boolean) = update { it.copy(streamingAnimations = v) }
    suspend fun setHaptics(v: Boolean) = update { it.copy(haptics = v) }
    override suspend fun setAllowMeteredDownloads(allow: Boolean) = update { it.copy(allowMeteredDownloads = allow) }
    suspend fun setNotificationsAsked(v: Boolean) = update { it.copy(notificationsAsked = v) }
    override suspend fun setSelectedModel(id: String?) = update { it.copy(selectedModelId = id) }
    suspend fun setOnboardingDone(v: Boolean) = update { it.copy(onboardingDone = v) }
    suspend fun setMinLogPriority(p: Int) = update { it.copy(minLogPriority = p.coerceIn(2, 7)) }
    suspend fun setLoadAttempt(modelId: String?) = update { it.copy(loadAttemptModelId = modelId) }
    suspend fun setLastLoadCrash(modelId: String?) = update { it.copy(lastLoadCrashModelId = modelId) }
    suspend fun setCalibration(modelId: String, c: Calibration) = update { it.copy(calibration = it.calibration + (modelId to c)) }
    suspend fun setSelectedImageModel(id: String?) = update { it.copy(selectedImageModelId = id) }
    suspend fun setImageGenSecPerStep(key: String, secPerStep: Float) = update { it.copy(imageGenSecPerStep = it.imageGenSecPerStep + (key to secPerStep)) }
    // ---- Experience level / developer sub-toggles ----
    suspend fun setExperienceLevel(l: ExperienceLevel) = update { it.copy(experienceLevel = l) }
    suspend fun setDevFlag(flag: DevFlag, v: Boolean) = update { flag.set(it, v) }
    // ---- Context policy ----
    suspend fun setContextPolicy(p: ContextPolicy) = update { it.copy(contextPolicy = p) }

    // ---- DownloadPrefs (models/ModelRepository.kt): snapshot reads of the hot state ----
    override val allowMeteredDownloads: Boolean get() = settings.value.allowMeteredDownloads
    override val selectedModelId: String? get() = settings.value.selectedModelId

    // ---- EtaStore (imagegen/ImageGenTypes.kt) ----
    /** Reads the file, not [settings]: ImageGenRepository calls this at construction, possibly before the first disk read landed. */
    override suspend fun load(): Map<String, Float> =
        store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first().toState().imageGenSecPerStep
    override suspend fun save(values: Map<String, Float>) = update { it.copy(imageGenSecPerStep = values) }
}

/** The preference keys; one per SettingsState field. Top level (not inside AppPrefs) so the pure conversions below are unit-testable without a Context. */
private object K {
    val perfPreset = stringPreferencesKey("perfPreset")
    val threads = intPreferencesKey("threads")
    val pinBigCores = booleanPreferencesKey("pinBigCores")
    val contextSize = intPreferencesKey("contextSize")
    val kvCache = stringPreferencesKey("kvCache")
    val flashAttention = booleanPreferencesKey("flashAttention")
    val useMmap = booleanPreferencesKey("useMmap")
    val poll = intPreferencesKey("poll")
    val keepModelLoaded = booleanPreferencesKey("keepModelLoaded")
    val params = stringPreferencesKey("params")
    val useModelSamplingDefaults = booleanPreferencesKey("useModelSamplingDefaults")
    val systemPrompt = stringPreferencesKey("systemPrompt")
    val thinking = booleanPreferencesKey("thinking")
    val imageDetail = stringPreferencesKey("imageDetail")
    val autoTrim = booleanPreferencesKey("autoTrim")
    val streamingAnimations = booleanPreferencesKey("streamingAnimations")
    val haptics = booleanPreferencesKey("haptics")
    val allowMeteredDownloads = booleanPreferencesKey("allowMeteredDownloads")
    val notificationsAsked = booleanPreferencesKey("notificationsAsked")
    val selectedModelId = stringPreferencesKey("selectedModelId")
    val onboardingDone = booleanPreferencesKey("onboardingDone")
    val minLogPriority = intPreferencesKey("minLogPriority")
    val loadAttemptModelId = stringPreferencesKey("loadAttemptModelId")
    val lastLoadCrashModelId = stringPreferencesKey("lastLoadCrashModelId")
    val calibration = stringPreferencesKey("calibration")
    val selectedImageModelId = stringPreferencesKey("selectedImageModelId")
    val imageGenSecPerStep = stringPreferencesKey("imageGenSecPerStep")
    // ---- Experience level; `developerMode` is the pre-tier boolean, still read for the migration and written for a downgrade ----
    val experienceLevel = stringPreferencesKey("experienceLevel")
    val developerMode = booleanPreferencesKey("developerMode")
    val showGenerationStats = booleanPreferencesKey("showGenerationStats")
    val showContextMeter = booleanPreferencesKey("showContextMeter")
    val showTurnDetails = booleanPreferencesKey("showTurnDetails")
    val showBenchmark = booleanPreferencesKey("showBenchmark")
    val showModelTechSpecs = booleanPreferencesKey("showModelTechSpecs")
    val showThermalInfo = booleanPreferencesKey("showThermalInfo")
    val showTokenCounter = booleanPreferencesKey("showTokenCounter")
    // ---- Context policy ----
    val contextPolicy = stringPreferencesKey("contextPolicy")
}

/** Preferences -> SettingsState; unknown or corrupt values fall back to the field default. Internal so a plain JVM test can drive the migrations. */
internal fun Preferences.toState(): SettingsState {
    val d = SettingsState()
    return SettingsState(
        perfPreset = enum(this[K.perfPreset], d.perfPreset),
        threads = this[K.threads] ?: d.threads,
        pinBigCores = this[K.pinBigCores] ?: d.pinBigCores,
        contextSize = this[K.contextSize] ?: d.contextSize,
        kvCache = enum(this[K.kvCache], d.kvCache),
        flashAttention = this[K.flashAttention] ?: d.flashAttention,
        useMmap = this[K.useMmap] ?: d.useMmap,
        poll = this[K.poll] ?: d.poll,
        keepModelLoaded = this[K.keepModelLoaded] ?: d.keepModelLoaded,
        params = decode(this[K.params], d.params),
        useModelSamplingDefaults = this[K.useModelSamplingDefaults] ?: d.useModelSamplingDefaults,
        systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
        thinking = this[K.thinking] ?: d.thinking,
        imageDetail = enum(this[K.imageDetail], d.imageDetail),
        streamingAnimations = this[K.streamingAnimations] ?: d.streamingAnimations,
        haptics = this[K.haptics] ?: d.haptics,
        allowMeteredDownloads = this[K.allowMeteredDownloads] ?: d.allowMeteredDownloads,
        notificationsAsked = this[K.notificationsAsked] ?: d.notificationsAsked,
        selectedModelId = this[K.selectedModelId],
        onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
        minLogPriority = this[K.minLogPriority] ?: d.minLogPriority,
        loadAttemptModelId = this[K.loadAttemptModelId],
        lastLoadCrashModelId = this[K.lastLoadCrashModelId],
        calibration = decode(this[K.calibration], d.calibration),
        selectedImageModelId = this[K.selectedImageModelId],
        imageGenSecPerStep = decode(this[K.imageGenSecPerStep], d.imageGenSecPerStep),
        // Installs from before the tiers only stored the boolean: developerMode=true was the "everything" user.
        experienceLevel = enumOrNull<ExperienceLevel>(this[K.experienceLevel])
            ?: (if (this[K.developerMode] == true) ExperienceLevel.DEVELOPER else d.experienceLevel),
        showGenerationStats = this[K.showGenerationStats] ?: d.showGenerationStats,
        showContextMeter = this[K.showContextMeter] ?: d.showContextMeter,
        showTurnDetails = this[K.showTurnDetails] ?: d.showTurnDetails,
        showBenchmark = this[K.showBenchmark] ?: d.showBenchmark,
        showModelTechSpecs = this[K.showModelTechSpecs] ?: d.showModelTechSpecs,
        showThermalInfo = this[K.showThermalInfo] ?: d.showThermalInfo,
        showTokenCounter = this[K.showTokenCounter] ?: d.showTokenCounter,
        // Installs that only ever stored the old boolean: autoTrim=false meant "fail when full" (STOP), true meant trimming (ROLLING).
        contextPolicy = enumOrNull<ContextPolicy>(this[K.contextPolicy])
            ?: (if (this[K.autoTrim] == false) ContextPolicy.STOP else d.contextPolicy),
    )
}

/** SettingsState -> Preferences, the inverse of [toState]. */
internal fun MutablePreferences.write(s: SettingsState) {
    this[K.perfPreset] = s.perfPreset.name
    this[K.threads] = s.threads
    this[K.pinBigCores] = s.pinBigCores
    this[K.contextSize] = s.contextSize
    this[K.kvCache] = s.kvCache.name
    this[K.flashAttention] = s.flashAttention
    this[K.useMmap] = s.useMmap
    this[K.poll] = s.poll
    this[K.keepModelLoaded] = s.keepModelLoaded
    this[K.params] = json.encodeToString(GenerationParams.serializer(), s.params)
    this[K.useModelSamplingDefaults] = s.useModelSamplingDefaults
    this[K.systemPrompt] = s.systemPrompt
    this[K.thinking] = s.thinking
    this[K.imageDetail] = s.imageDetail.name
    this[K.streamingAnimations] = s.streamingAnimations
    this[K.haptics] = s.haptics
    this[K.allowMeteredDownloads] = s.allowMeteredDownloads
    this[K.notificationsAsked] = s.notificationsAsked
    setOrRemove(K.selectedModelId, s.selectedModelId)
    this[K.onboardingDone] = s.onboardingDone
    this[K.minLogPriority] = s.minLogPriority
    setOrRemove(K.loadAttemptModelId, s.loadAttemptModelId)
    setOrRemove(K.lastLoadCrashModelId, s.lastLoadCrashModelId)
    this[K.calibration] = json.encodeToString(s.calibration)
    setOrRemove(K.selectedImageModelId, s.selectedImageModelId)
    this[K.imageGenSecPerStep] = json.encodeToString(s.imageGenSecPerStep)
    // ---- Experience level ----
    this[K.experienceLevel] = s.experienceLevel.name
    this[K.developerMode] = s.developerMode                      // kept in sync so a downgrade still reads something sensible
    this[K.showGenerationStats] = s.showGenerationStats
    this[K.showContextMeter] = s.showContextMeter
    this[K.showTurnDetails] = s.showTurnDetails
    this[K.showBenchmark] = s.showBenchmark
    this[K.showModelTechSpecs] = s.showModelTechSpecs
    this[K.showThermalInfo] = s.showThermalInfo
    this[K.showTokenCounter] = s.showTokenCounter
    this[K.contextPolicy] = s.contextPolicy.name
    this[K.autoTrim] = s.contextPolicy != ContextPolicy.STOP      // kept in sync so a downgrade still reads something sensible
}

private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

private inline fun <reified E : Enum<E>> enum(name: String?, default: E): E = enumOrNull<E>(name) ?: default
private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

private inline fun <reified T> decode(text: String?, default: T): T =
    if (text.isNullOrEmpty()) default else runCatching { json.decodeFromString<T>(text) }.getOrDefault(default)

/** ignoreUnknownKeys: a newer build's fields must not break an older stored blob (and vice versa). */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
