package to.eyed.inferno.engine

import to.eyed.inferno.models.LocalModel

enum class FinishReason { EOS, LENGTH, CONTEXT_FULL, CANCELLED, ERROR;
    companion object { fun fromNative(code: Int) = when (code) { 1 -> EOS; 2 -> LENGTH; 3 -> CONTEXT_FULL; 4 -> CANCELLED; else -> ERROR } } }

enum class KvCacheType(val ggmlType: Int) { F16(1), Q8_0(8), Q4_0(2) }

/**
 * Everything needed to create a llama_context. Produced by ContextManager.plan().
 * Invariants (enforced in init, so a native GGML_ASSERT can never be reached from a valid instance):
 *  - nCtx is a multiple of 256 and >= ContextManager.MIN_CTX;
 *  - nBatch == nUbatch >= 512 and a multiple of 64, and >= the largest image chunk the model can emit (5.2 plan);
 *  - kvType != F16 requires flashAttention == true (quantized V cache needs FA; llama.cpp aborts otherwise);
 *  - nOutputsMax >= 1 (every batch requests logits for at most one token, 4.5 step 2).
 */
data class ContextConfig(
    val nCtx: Int,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val nThreads: Int = 4,
    val nThreadsBatch: Int = 4,
    val bigCoresOnly: Boolean = true,
    val flashAttention: Boolean = true,
    val kvType: KvCacheType = KvCacheType.F16,
    val swaFull: Boolean = true,       // planner sets false for GEMMA3/GEMMA4 (iSWA cache); default matches the planner for every other family
    val poll: Int = 50,
    val nOutputsMax: Int = 8,
) {
    init {
        require(nCtx % 256 == 0 && nCtx >= 2048) { "nCtx must be a multiple of 256 and >= 2048" }
        require(nBatch == nUbatch && nBatch >= 512 && nBatch % 64 == 0) { "nBatch/nUbatch" }
        require(kvType == KvCacheType.F16 || flashAttention) { "quantized KV cache requires flash attention" }
        require(nOutputsMax >= 1)
    }
    /**
     * FLASH_ATTN encoding: 1 = LLAMA_FLASH_ATTN_TYPE_ENABLED, 0 = LLAMA_FLASH_ATTN_TYPE_DISABLED. Never -1 (AUTO):
     * on the CPU backend AUTO resolves to enabled, which would make the Settings toggle a no-op. A quantized V
     * cache always forces 1 (see init invariant; AppViewModel.setKvCache also flips the pref, 5.5).
     */
    fun toNative(): IntArray = IntArray(CtxP.SIZE).also {
        it[CtxP.N_CTX] = nCtx; it[CtxP.N_BATCH] = nBatch; it[CtxP.N_UBATCH] = nUbatch; it[CtxP.N_THREADS] = nThreads
        it[CtxP.N_THREADS_BATCH] = nThreadsBatch; it[CtxP.BIG_CORES_ONLY] = if (bigCoresOnly) 1 else 0
        it[CtxP.FLASH_ATTN] = when { kvType != KvCacheType.F16 -> 1; flashAttention -> 1; else -> 0 }
        it[CtxP.TYPE_K] = kvType.ggmlType; it[CtxP.TYPE_V] = kvType.ggmlType
        it[CtxP.SWA_FULL] = if (swaFull) 1 else 0; it[CtxP.POLL] = poll; it[CtxP.N_OUTPUTS_MAX] = nOutputsMax
    }
}

/**
 * Memory plan for one (model, ContextConfig). modelResidentBytes = weights repacked into anonymous RAM;
 * modelMappedBytes = weights left file-backed (evictable, not counted in the budget). encoderPeakBytes = catalog
 * `encoderPeakBytes` (activation peak of the vision encoder at the clamped image size) or 0 for text-only.
 * mmproj + encoder are counted even though the projector is loaded lazily: the budget must hold for the first image turn.
 */
data class MemoryEstimate(
    val modelBytes: Long,             // resident + mapped (== llama_model_size within 10 %)
    val modelResidentBytes: Long,
    val modelMappedBytes: Long,
    val kvBytes: Long,
    val computeBytes: Long,
    val mmprojBytes: Long,
    val clipComputeBytes: Long,       // MemEst.CLIP_COMPUTE
    val encoderPeakBytes: Long,       // from CatalogModel (0 for imports / text-only)
    val deviceTotal: Long,
    val approximate: Boolean = false, // true when produced analytically in Kotlin (slider preview), false from native
) {
    val visionBytes: Long get() = mmprojBytes + maxOf(clipComputeBytes, encoderPeakBytes)
    val totalBytes: Long get() = modelResidentBytes + kvBytes + computeBytes + visionBytes
}

/** Facts about the model currently in memory. */
data class LoadedModel(
    val model: LocalModel,
    val arch: String,
    val description: String,
    val nParams: Long,
    val sizeBytes: Long,
    val nCtxTrain: Int,
    val nLayer: Int,
    val nEmbd: Int,
    val nHeadKv: Int,
    val nSwa: Int,
    val hasVision: Boolean,            // an mmproj file exists for this model (may or may not be resident)
    val visionAllowed: Boolean,        // Plan.visionAllowed: false => images disabled this session (memory); InputBar.canAttachImages = hasVision && visionAllowed
    val visionResident: Boolean,       // mtmd_context currently loaded (lazy: false after load, true after the first image turn, false again after a trim / text-only unload)
    val templateSupported: Boolean,
    val templateName: String?,
    val context: ContextConfig,        // as created (nCtx = actual padded value)
    val estimate: MemoryEstimate?,
    val loadMs: Long,
    val calibration: Calibration?,     // measured on this phone (5.5 AppViewModel auto-bench), null until measured
)

/** Measured numbers per model on this device; persisted in AppPrefs (`calibration` JSON map keyed by modelId). */
@kotlinx.serialization.Serializable
data class Calibration(
    val ppTps: Double, val tgTps: Double, val loadMs: Long, val at: Long,
    /** Per ImageDetail.name: last measured image encode (ms, image tokens); drives the determinate "Reading image" indicator (5.6 Indicators). */
    val imageEncode: Map<String, ImageEncodeSample> = emptyMap(),
)
@kotlinx.serialization.Serializable
data class ImageEncodeSample(val ms: Long, val tokens: Int)

data class GenerationStats(
    val promptTokens: Int,
    val reusedTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val imageEncodeMs: Long,
    val kvUsedTokens: Int,
    val nCtx: Int,
) {
    val decodeTps: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
    val prefillTps: Double get() = if (prefillMs > 0) (promptTokens - reusedTokens) * 1000.0 / prefillMs else 0.0
    val contextFraction: Float get() = if (nCtx > 0) kvUsedTokens.toFloat() / nCtx else 0f
}

/** Events emitted by InferenceEngine.generate(). Exactly one terminal event (Done or Error) per flow. */
sealed interface GenerationEvent {
    /** done/total in prompt tokens (Phase.TEXT/IMAGE semantics, 3.1); encodingImage == (phase == Phase.IMAGE). */
    data class Prefill(val done: Int, val total: Int, val encodingImage: Boolean) : GenerationEvent
    /** Emitted once, before Prefill, when the engine had to resume from Suspended (context recreated / projector reloaded). */
    data class Resuming(val phase: String) : GenerationEvent
    data class Token(val text: String) : GenerationEvent                // visible answer delta
    data class Thinking(val text: String) : GenerationEvent             // reasoning delta (inside think tags)
    data class Done(val reason: FinishReason, val stats: GenerationStats) : GenerationEvent
    data class Error(val message: String) : GenerationEvent
    /** Prompt does not fit: caller must truncate (ContextManager) and retry. Terminal. */
    data object NeedsTruncation : GenerationEvent
}

/** True for the events after which the flow completes (Done, Error, NeedsTruncation). */
val GenerationEvent.isTerminal: Boolean
    get() = this is GenerationEvent.Done || this is GenerationEvent.Error || this is GenerationEvent.NeedsTruncation

sealed interface EngineState {
    data object Idle : EngineState                                               // no model in memory
    data class Loading(val model: LocalModel, val progress: Float?, val phase: String) : EngineState  // phase: "Reading weights" | "Loading vision" | "Creating context" | "Calibrating"
    data class Ready(val loaded: LoadedModel) : EngineState
    data class Generating(val loaded: LoadedModel, val conversationId: String) : EngineState
    /**
     * Weights resident, llama_context released (and the projector too when visionReleased) after a memory trim
     * (4.3). The next generate()/countPromptTokens() transparently resumes (5.2). Not "Ready": loadedOrNull == null,
     * but the UI keeps the composer enabled and the chip shows the model name in I500 with a `Zap` icon.
     */
    data class Suspended(val loaded: LoadedModel, val visionReleased: Boolean) : EngineState
    data class Error(val message: String, val model: LocalModel?) : EngineState
}
val EngineState.loadedOrNull: LoadedModel? get() = when (this) { is EngineState.Ready -> loaded; is EngineState.Generating -> loaded; else -> null }
/** Whatever holds weights right now (Ready, Generating, Suspended): what an unload or a re-plan gives back. */
val EngineState.loadedAny: LoadedModel? get() = when (this) { is EngineState.Ready -> loaded; is EngineState.Generating -> loaded; is EngineState.Suspended -> loaded; else -> null }
/**
 * Anonymous RAM this loaded model holds by its own plan: repacked weights + KV + compute, plus the projector when
 * resident. Mapped weights are page cache and already count as available.
 */
val LoadedModel.residentFootprintBytes: Long get() = estimate?.let { e -> e.modelResidentBytes + e.kvBytes + e.computeBytes + (if (visionResident) e.mmprojBytes else 0L) } ?: 0L
/** Model selected and either usable now or resumable without user action (Ready, Generating, Suspended, Loading). */
val EngineState.modelOrNull: LocalModel? get() = when (this) { is EngineState.Ready -> loaded.model; is EngineState.Generating -> loaded.model
    is EngineState.Suspended -> loaded.model; is EngineState.Loading -> model; else -> null }

/** Loading phase labels (EngineState.Loading.phase / GenerationEvent.Resuming.phase). */
object LoadPhase {
    const val WEIGHTS = "Reading weights"; const val VISION = "Loading vision"
    const val CONTEXT = "Creating context"; const val CALIBRATING = "Calibrating"
}

/** Prompt input to the engine (already truncated by ContextManager). */
data class PromptMessage(val role: String, val content: String, val imageIds: List<String> = emptyList())
/** Decoded image ready for mtmd; rgb == null means "count only". */
data class PromptImage(val id: String, val width: Int, val height: Int, val rgb: ByteArray?)

open class EngineException(message: String) : RuntimeException(message)

/** The load-time budget gate refused the plan (free RAM moved since it was made): the caller may re-plan once. */
class BudgetGateException(message: String) : EngineException(message)
