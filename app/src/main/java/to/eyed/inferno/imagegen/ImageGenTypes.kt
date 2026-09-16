package to.eyed.inferno.imagegen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.sd.ImageEngineState
import to.eyed.inferno.sd.ImageGenEvent
import to.eyed.inferno.sd.ImageGenRequest
import to.eyed.inferno.sd.ImageModelSpec

/** App-level image-generation types. The engine contract lives in `to.eyed.inferno.sd`. */

/** Output size presets of the Create screen. Both SD 1.5 picks were distilled at 512; 384 is the fast option. */
enum class ImageSizePreset(val px: Int) {
    S384(384),
    S512(512);

    companion object {
        fun fromPx(px: Int): ImageSizePreset = entries.firstOrNull { it.px == px } ?: S512
    }
}

/**
 * What the user asked for on the Create screen.
 *
 * @property steps null = the model's default; otherwise clamped to the catalog range.
 * @property seed -1 = random (the used seed is reported in the resulting [GenerationRecord]).
 */
data class ImageGenParams(
    val modelId: String,
    val prompt: String,
    val size: ImageSizePreset = ImageSizePreset.S512,
    val steps: Int? = null,
    val seed: Long = -1,
)

/** Everything a generation is described by, minus the files; what [GeneratedImageStore.save] persists. */
data class GenerationMeta(
    val modelId: String,
    val prompt: String,
    val negative: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long,
    val sampler: String,
    val totalMs: Long,
    val createdAt: Long,
)

/** One persisted generation (mirrors `GeneratedImageEntity`; the data layer maps between the two). */
data class GenerationRecord(
    val id: String,
    val modelId: String,
    val prompt: String,
    val negative: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long,
    val sampler: String,
    val totalMs: Long,
    val createdAt: Long,
    /** Absolute path of the full-size PNG under `files/images/gen/`. */
    val path: String,
    /** Absolute path of the 256 px thumbnail, null when not generated. */
    val thumbPath: String? = null,
)

sealed interface ImageGenUiState {
    data object Idle : ImageGenUiState

    /** The model (or the shared TAESD file) is not on disk; the screen shows the model card's Download action. */
    data class NeedsDownload(val modelId: String) : ImageGenUiState

    /** Waiting for the LLM engine to release, the job gate, or the sd.cpp model load. */
    data object Loading : ImageGenUiState

    /**
     * @property step Denoise steps finished so far (0 before the first). [step] == [total] means TAESD decode.
     * @property etaSeconds Remaining wall time from the ETA model, rounded up; never negative.
     * @property previewPng Latest latent-projection preview (kept from the previous step when the current
     *   event carried none) so the blurred backdrop never flickers.
     */
    data class Generating(
        val step: Int,
        val total: Int,
        val etaSeconds: Int,
        val previewPng: ByteArray?,
    ) : ImageGenUiState {
        val isDecoding: Boolean get() = total > 0 && step >= total

        // Content equality for the preview so StateFlow's conflation compares pixels, not references.
        override fun equals(other: Any?): Boolean = other is Generating &&
            step == other.step && total == other.total && etaSeconds == other.etaSeconds &&
            (previewPng?.contentEquals(other.previewPng) ?: (other.previewPng == null))

        override fun hashCode(): Int =
            ((step * 31 + total) * 31 + etaSeconds) * 31 + (previewPng?.contentHashCode() ?: 0)
    }

    data class Done(val record: GenerationRecord) : ImageGenUiState

    data class Error(val message: String) : ImageGenUiState
}

// ------------------------------------------------------------------------------------------------
// Seams towards the other packages. Implemented in the composition root; faked in tests.

/** Implemented by the LLM side: unload the text model so only one native engine is resident. */
fun interface EngineCoordinator {
    suspend fun releaseForImageGen()

    /**
     * An image run (load + generate) is starting / has ended. The app side keeps the process alive with the
     * foreground service (EngineService, HEADING_IMAGE) while a run is active and the app is in the background,
     * exactly as it does for a text turn; a 30-90 s SD run in a plain cached process is frozen or killed.
     * Default no-op so fakes and tests stay one lambda.
     */
    fun onImageJob(active: Boolean, modelName: String) {}
}

/** The process-wide single-native-job mutex, shared with text and vision jobs. */
interface JobGate {
    suspend fun <T> withJob(tag: String, block: suspend () -> T): T
}

/** Writes the PNG + 256 px thumb under `files/images/gen/` and the Room row. */
fun interface GeneratedImageStore {
    suspend fun save(png: ByteArray, meta: GenerationMeta): GenerationRecord
}

/** DataStore-backed persistence of the ETA model (keys from [EtaModel.key]). */
interface EtaStore {
    suspend fun load(): Map<String, Float>
    suspend fun save(values: Map<String, Float>)
}

/** Resolves downloaded files; null when the file is missing or incomplete (the `ModelFiles` layout). */
interface ImageModelFiles {
    fun modelPath(model: ImageCatalogModel): String?
    fun taesdPath(): String?
}

/** Engine-shaped seam so the repository can be unit-tested; [ImageEngineAdapter] wraps the real engine. */
interface ImageEngineApi {
    val state: StateFlow<ImageEngineState>
    suspend fun load(spec: ImageModelSpec, modelPath: String, taesdPath: String)
    fun generate(req: ImageGenRequest): Flow<ImageGenEvent>
    suspend fun unload()
    fun estimateBytes(spec: ImageModelSpec, modelBytes: Long): Long
}

// ------------------------------------------------------------------------------------------------

/**
 * Wall-time estimator: an exponential moving average of seconds per denoise step per (model, size),
 * seeded with the Seeker measurements from the catalog (13.5 s/step DreamShaper, 6.9 s SDXS at 512 px)
 * and scaled by pixel count for sizes without an observation. Decode and fixed overhead (text encoding,
 * lazy weight load) are taken from the catalog and not learned: they are small and nearly constant.
 *
 * Not thread-safe by design: the repository mutates it from its own scope only.
 */
class EtaModel(
    private val catalog: List<ImageCatalogModel> = ImageModelCatalog.models,
    initial: Map<String, Float> = emptyMap(),
) {
    private val secPerStep = HashMap<String, Float>()

    init {
        merge(initial)
    }

    /** Adds persisted observations; they win over the seeds. */
    fun merge(values: Map<String, Float>) {
        for ((k, v) in values) if (v.isFinite() && v > 0f) secPerStep[k] = v
    }

    fun snapshot(): Map<String, Float> = secPerStep.toMap()

    fun secondsPerStep(modelId: String, px: Int): Float {
        secPerStep[key(modelId, px)]?.let { return it }
        val model = catalog.firstOrNull { it.id == modelId }
        val base = model?.secondsPerStepAtNative ?: FALLBACK_SEC_PER_STEP
        val native = model?.spec?.nativeSize ?: 512
        // Observed at another size? Prefer that over the seed: it carries this phone's real speed.
        val observedNative = secPerStep[key(modelId, native)]
        return (observedNative ?: base) * scale(px, native)
    }

    fun decodeSeconds(modelId: String, px: Int): Float {
        val model = catalog.firstOrNull { it.id == modelId }
        return (model?.decodeSecondsAtNative ?: FALLBACK_DECODE) * scale(px, model?.spec?.nativeSize ?: 512)
    }

    /** Full estimate for one image, including the fixed per-run overhead. */
    fun estimateSeconds(modelId: String, px: Int, steps: Int): Float {
        val model = catalog.firstOrNull { it.id == modelId }
        return steps * secondsPerStep(modelId, px) + decodeSeconds(modelId, px) + (model?.overheadSeconds ?: 0f)
    }

    /** Remaining time once [stepsDone] of [total] steps are finished ([stepsDone] >= [total] = decoding). */
    fun remainingSeconds(modelId: String, px: Int, stepsDone: Int, total: Int): Float {
        val left = (total - stepsDone).coerceAtLeast(0)
        return left * secondsPerStep(modelId, px) + decodeSeconds(modelId, px)
    }

    /** Feeds one measured run; alpha 0.3 follows a thermal slowdown within a few images without jitter. */
    fun observe(modelId: String, px: Int, secondsPerStep: Float) {
        if (!secondsPerStep.isFinite() || secondsPerStep <= 0f) return
        // The prior is whatever we would currently estimate (a stored value, or the catalog seed), so the
        // very first measurement is smoothed against the seed rather than replacing it outright.
        val prior = secondsPerStep(modelId, px)
        secPerStep[key(modelId, px)] = prior * (1f - ALPHA) + secondsPerStep * ALPHA
    }

    companion object {
        const val ALPHA = 0.3f
        private const val FALLBACK_SEC_PER_STEP = 10f
        private const val FALLBACK_DECODE = 3.8f

        fun key(modelId: String, px: Int) = "$modelId:$px"

        /** Diffusion cost is linear in latent pixels (UNet attention is the exception but small at <= 512). */
        private fun scale(px: Int, native: Int): Float = (px.toLong() * px).toFloat() / (native.toLong() * native)
    }
}
