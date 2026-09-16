package to.eyed.inferno.models

import to.eyed.inferno.sd.ImageArch
import to.eyed.inferno.sd.ImageModelSpec
import to.eyed.inferno.sd.ImageSampler
import to.eyed.inferno.sd.ImageScheduler

/**
 * One downloadable file of an image model. Deliberately a separate, minimal type rather than WP3's
 * `ModelFileSpec`: image checkpoints have no `Quant` enum value that fits (sd.cpp GGUFs mix a q8_0 UNet
 * with an f16 text encoder) and the TAESD file is a safetensors, not a GGUF. Same URL pattern, byte-exact
 * sizes and LFS sha256 (from the HF API `?blobs=true`, 2026-09-16) so `ModelDownloader` verifies length and
 * hash on completion: a resumed partial of a re-uploaded same-size file must not pass as complete.
 */
data class ImageModelFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/**
 * App-side catalog row for a text-to-image model: the engine [spec] plus everything the Create screen
 * shows (tier label, licence, size on disk, per-size time estimate) and the step range the stepper allows.
 *
 * @property estSecondsBySize Measured (512) / pixel-scaled (384) wall time for one image at the default
 *   step count on the Seeker, 4 pinned A78 threads, TAESD decode, cool phone. Seeds the ETA model.
 * @property peakRamBytes Measured peak RSS for a 512 px image; shown on the card and used by the 0.85 x
 *   budget refusal rule (spec 12.3 a) together with `ImageEngine.estimateBytes`.
 */
data class ImageCatalogModel(
    val spec: ImageModelSpec,
    val tierLabel: String,
    val license: String,
    val blurb: String,
    val file: ImageModelFile,
    val minSteps: Int,
    val maxSteps: Int,
    val estSecondsBySize: Map<Int, Int>,
    val peakRamBytes: Long,
    /** Measured seconds per denoise step at [ImageModelSpec.nativeSize]; seeds `EtaModel`. */
    val secondsPerStepAtNative: Float,
    /** Measured TAESD decode seconds at [ImageModelSpec.nativeSize]. */
    val decodeSecondsAtNative: Float,
) {
    val id: String get() = spec.id
    val displayName: String get() = spec.displayName
    val defaultSteps: Int get() = spec.steps

    /** Text encoding + lazy weight load: whatever the measured total is not explained by steps and decode. */
    val overheadSeconds: Float
        get() = (spec.estSeconds - spec.steps * secondsPerStepAtNative - decodeSecondsAtNative).coerceAtLeast(0f)

    /** True when the stepper is meaningful (one-step distilled models have nothing to adjust). */
    val stepsAdjustable: Boolean get() = maxSteps > minSteps

    fun clampSteps(steps: Int): Int = steps.coerceIn(minSteps, maxSteps)
}

/**
 * The two image models of v1 (spec 12.5) and the TAESD autoencoder they share. Both are SD 1.5-family,
 * which is why a single TAESD file serves both; it is stored in its own directory `files/models/taesd/`
 * so deleting one model never breaks the other. Files live under `files/models/<id>/<fileName>`, the same
 * layout `ModelFiles` uses for LLMs, so the storage bar and the downloader need no special case.
 */
object ImageModelCatalog {
    /** Directory id (under `files/models/`) of the shared TAESD file. */
    const val TAESD_DIR_ID = "taesd"

    private const val HF = "https://huggingface.co"

    /**
     * Full TAESD checkpoint (encoder + decoder). The decoder-only `taesd_decoder.safetensors` is rejected by
     * sd.cpp's validator when the checkpoint embeds its own VAE, so the full file is the only option. MIT.
     */
    val taesd = ImageModelFile(
        fileName = "diffusion_pytorch_model.safetensors",
        url = "$HF/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors",
        sizeBytes = 9_793_292L,
        sha256 = "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549",
    )

    val dreamShaper = ImageCatalogModel(
        spec = ImageModelSpec(
            id = "dreamshaper-8-lcm-q8_0",
            displayName = "DreamShaper 8 LCM",
            arch = ImageArch.SD15,
            textFile = "DreamShaper8_LCM_q8_0.gguf",
            taesdFile = "diffusion_pytorch_model.safetensors",
            steps = 4,
            cfgScale = 1.0f,
            sampler = ImageSampler.LCM,
            scheduler = ImageScheduler.DISCRETE,
            nativeSize = 512,
            estSeconds = 63,        // measured: 4 x 13.5 s/step + 3.8 s TAESD decode
        ),
        tierLabel = "Quality",
        license = "CreativeML OpenRAIL-M",
        blurb = "Detailed and versatile; about a minute per image.",
        file = ImageModelFile(
            fileName = "DreamShaper8_LCM_q8_0.gguf",
            url = "$HF/haven-ai-companion/dreamshaper8-lcm-gguf/resolve/main/DreamShaper8_LCM_q8_0.gguf",
            sizeBytes = 1_802_206_336L,
            sha256 = "dd06826d1eca711efa38190395a5ee949d0427fc6422c39433358a815e5b066c",
        ),
        minSteps = 2,
        maxSteps = 8,
        estSecondsBySize = mapOf(384 to 33, 512 to 63),
        peakRamBytes = 2_000_000_000L,
        secondsPerStepAtNative = 13.5f,
        decodeSecondsAtNative = 3.8f,
    )

    val sdxs = ImageCatalogModel(
        spec = ImageModelSpec(
            id = "sdxs-512-q8_0",
            displayName = "SDXS-512",
            arch = ImageArch.SD15,
            textFile = "sdxs-512-tinySDdistilled_Q8_0.gguf",
            taesdFile = "diffusion_pytorch_model.safetensors",
            steps = 1,
            cfgScale = 1.0f,
            sampler = ImageSampler.EULER,
            scheduler = ImageScheduler.DISCRETE,
            nativeSize = 512,
            estSeconds = 12,        // measured: 6.9 s single step + 3.7 s TAESD decode
        ),
        tierLabel = "Instant",
        license = "OpenRAIL++",
        blurb = "Simpler images in about ten seconds.",
        file = ImageModelFile(
            fileName = "sdxs-512-tinySDdistilled_Q8_0.gguf",
            url = "$HF/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf",
            sizeBytes = 682_847_200L,
            sha256 = "409ab23582ee074c6b9d5395784fc0741b0599fb9d138686c69087c71678eb6a",
        ),
        minSteps = 1,
        maxSteps = 1,
        estSecondsBySize = mapOf(384 to 7, 512 to 12),
        peakRamBytes = 1_100_000_000L,
        secondsPerStepAtNative = 6.9f,
        decodeSecondsAtNative = 3.7f,
    )

    /** Picker order: the quality pick first, matching the LLM catalog's "recommended first" convention. */
    val models: List<ImageCatalogModel> = listOf(dreamShaper, sdxs)

    fun byId(id: String): ImageCatalogModel? = models.firstOrNull { it.id == id }

    /** Bytes a model needs on disk including the shared TAESD file (shown on the card before download). */
    fun bytesOnDisk(model: ImageCatalogModel): Long = model.file.sizeBytes + taesd.sizeBytes
}
