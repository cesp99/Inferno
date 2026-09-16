package to.eyed.inferno.models

import kotlinx.serialization.Serializable

/** SPECIALIST is reserved (spec 12.2) for OCR / task models so they can be added without a schema change. */
enum class ModelTier(val label: String) {
    FASTEST("Fastest"), BALANCED("Balanced"), QUALITY("Best quality"), LEGACY("More models"), SPECIALIST("Specialists")
}
enum class Quant { Q4_0, Q4_K_M, Q8_0, F16, IQ4_NL, OTHER }
enum class ModelFamily { QWEN35, QWEN3VL, GEMMA4, GEMMA3, LFM2VL, MINICPMV, SMOLVLM, INTERNVL, UNKNOWN }

/**
 * How to handle reasoning for this model family. The built-in llama_chat_apply_template has no `enable_thinking`
 * kwarg, so both directions are driven by an assistant prefix appended after the generation prompt:
 *   assistantPrefix = if (thinkingEnabled) enablePrefix else disablePrefix   (5.2 InferenceEngine.generate)
 * A null prefix means "append nothing" (the model's default behaviour applies). `hasTags` gates the Think chip.
 */
@Serializable
data class ThinkingSpec(
    val openTag: String? = null,        // e.g. "<think>"
    val closeTag: String? = null,       // e.g. "</think>"
    val defaultOn: Boolean = false,
    /** Assistant prefix that suppresses thinking (user turned it off). Qwen3.5/Qwen3/MiniCPM-V 4.6: "<think>\n\n</think>\n\n". */
    val disablePrefix: String? = null,
    /** Assistant prefix that forces thinking on. Qwen3.5/Qwen3: "<think>\n"; MiniCPM-V 4.6: null (thinks by default). */
    val enablePrefix: String? = null,
) { val hasTags: Boolean get() = openTag != null && closeTag != null }

/**
 * Image pre-processing / token budget presets (Section 8; edge values per 12.2: every encoder costs 5-6 s at
 * 448 px on this phone and LFM2.5 at 1024 px takes ~90 s, so BALANCED clamps the long edge to 448 px, FAST to
 * 336 px and HIGH defers to the catalog `maxImageEdgePx`; ImageUtil clamps min(detail.maxEdgePx, catalog)).
 */
enum class ImageDetail(val label: String, val maxEdgePx: Int, val maxTokens: Int) {
    FAST("Fast", 336, 256), BALANCED("Balanced", 448, 512), HIGH("High detail", 1536, 1024)
}

@Serializable
data class ModelFileSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,         // verified when non-null
    val quant: Quant,
)

/**
 * What the downloader and the storage layout actually operate on (additive, WP3). Both catalog file types map
 * to it: a [ModelFileSpec] of a text model (subdir = the model id) and WP9b's `ImageModelFile` (subdir = the
 * image model id, or `ImageModelCatalog.TAESD_DIR_ID` for the TAESD decoder both image models share). The
 * file lives at `files/models/<subdir>/<fileName>`, with `<fileName>.part(.json)` next to it while transferring.
 */
data class DownloadableFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val subdir: String,
    val sha256: String? = null,
) {
    /** Content check after the transfer: GGUF magic for .gguf, the safetensors header for .safetensors, none otherwise. */
    val isGguf: Boolean get() = fileName.endsWith(".gguf", ignoreCase = true)
    val isSafetensors: Boolean get() = fileName.endsWith(".safetensors", ignoreCase = true)
}

@Serializable
data class CatalogModel(
    val id: String,                     // stable, e.g. "qwen3.5-2b-q4_0"
    val displayName: String,            // "Qwen3.5 2B"
    val family: ModelFamily,
    val tier: ModelTier,
    val paramsB: Float,                 // total params in billions
    val license: String,
    val contextMax: Int,                // model native
    val defaultCtx: Int,                // Section 8
    val kvBytesPerTokenF16: Int,        // for UI estimates before load
    val text: ModelFileSpec,
    val mmproj: ModelFileSpec?,         // null => text-only (not used by v1 catalog, kept for imports)
    val thinking: ThinkingSpec,
    val sampling: SamplingDefaults,
    val imageTokensMin: Int,            // model's projector min (0 = model default)
    val imageTokensMax: Int,            // the LARGEST image chunk this projector can emit on this phone (n_batch floor); for fixed-tiling projectors this is the hard tile total, not a cap
    /** True when clip.cpp honours image_min/max_tokens (qwen-vl / pixtral / LFM2 dynamic-resolution branches). False => pass 0 and rely on maxImageEdgePx. */
    val dynamicResolution: Boolean,
    /** Hard cap on the long edge handed to the encoder for this model (ImageUtil.toPromptImage clamps min(detail.maxEdgePx, this)). */
    val maxImageEdgePx: Int,
    /** Peak encoder activation memory (bytes) at maxImageEdgePx, added to MemoryEstimate; measured or conservative estimate. */
    val encoderPeakBytes: Long,
    val estTgTps: String,               // "8-12" display only until benchmarked (replaced by Calibration when present)
    val blurb: String,                  // one line
    val recommended: Boolean = false,
    val minLlamaBuild: Int = 10991,
    /** Measured image-encode wall time at a 448 px long edge on the Seeker (bench-seeker.md); 0 = not measured. The "Reading image · ~N s" estimate scales it by pixel count. */
    val encodeMsAt448: Int = 0,
) {
    /** Download order: text first, then the projector (Downloading.fileIndex 1/2, 2/2). */
    val files: List<ModelFileSpec> get() = listOfNotNull(text, mmproj)
    val totalBytes: Long get() = text.sizeBytes + (mmproj?.sizeBytes ?: 0L)
    val hasVision: Boolean get() = mmproj != null
}

@Serializable
data class SamplingDefaults(val temperature: Float, val topP: Float, val topK: Int, val minP: Float, val repeatPenalty: Float = 1.0f, val presencePenalty: Float = 0f)

/** A model that exists on disk (catalog or imported). */
data class LocalModel(
    val id: String,                     // catalog id, or "import-<sha8>"
    val displayName: String,
    val textPath: String,
    val mmprojPath: String?,
    val textBytes: Long,
    val mmprojBytes: Long,
    val quant: Quant,
    val catalog: CatalogModel?,         // null for imports
    val importedAt: Long,
) { val totalBytes get() = textBytes + mmprojBytes; val hasVision get() = mmprojPath != null }

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object Queued : DownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val bytesPerSec: Long, val fileIndex: Int, val fileCount: Int) : DownloadState {
        val fraction: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }
    /** Partial `.part` on disk, not transferring: after user cancel, onTimeout, network loss, force-stop/process death. Resume keeps the bytes. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val reason: String?) : DownloadState {
        val fraction: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }
    data object Verifying : DownloadState
    data object Downloaded : DownloadState
    /** Terminal until Retry; `resumable = false` means the .part was discarded (license/404/size mismatch). */
    data class Failed(val message: String, val resumable: Boolean) : DownloadState
}

/** One row in the model manager: catalog entry and/or local file. */
data class ModelEntry(val id: String, val catalog: CatalogModel?, val local: LocalModel?, val download: DownloadState) {
    val displayName get() = local?.displayName ?: catalog!!.displayName
    val isDownloaded get() = local != null
    val license: String get() = catalog?.license ?: "Unknown (imported)"
}

/** modelsBytes includes complete files AND `.part` partials so the storage bar never hides paused downloads. */
data class StorageInfo(val modelsBytes: Long, val partialBytes: Long, val freeBytes: Long, val totalBytes: Long)
