package to.eyed.inferno.sd

/** Shared contract of the image-generation engine. Other packages import, never redefine. */

enum class ImageArch { SD15, SDXL }

enum class ImageSampler { LCM, EULER, EULER_A }

enum class ImageScheduler { DISCRETE, SGM_UNIFORM }

/**
 * A catalog entry for a text-to-image checkpoint.
 *
 * @property textFile GGUF file name (single-file checkpoint: TE + UNet + VAE).
 * @property taesdFile Tiny AutoEncoder file name; must be the FULL `diffusion_pytorch_model.safetensors`
 *   (the decoder-only file fails sd.cpp validation with checkpoints that embed a VAE). Mandatory on the
 *   phone: the real VAE decode costs ~80 s at 512 px on the Seeker vs ~4 s for TAESD.
 * @property nativeSize Training resolution the few-step models were distilled at (512 for every phone pick).
 * @property estSeconds Measured wall time for one [nativeSize] image on the Seeker with 4 pinned threads.
 */
data class ImageModelSpec(
    val id: String,
    val displayName: String,
    val arch: ImageArch,
    val textFile: String,
    val taesdFile: String,
    val steps: Int,
    val cfgScale: Float,
    val sampler: ImageSampler,
    val scheduler: ImageScheduler,
    val nativeSize: Int = 512,
    val estSeconds: Int,
)

/**
 * @property seed -1 picks a random seed; the seed actually used is reported in [ImageGenEvent.Done].
 * @property threads Informational: the ggml thread count is fixed at load time by [ImageEngine]
 *   (sd.cpp has no per-request thread API); kept so callers can record what they asked for.
 * @property previews Emit a latent-projection preview with every [ImageGenEvent.Progress] (cheap;
 *   pointless for 1-step models, so default off there).
 */
data class ImageGenRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long = -1,
    val sampler: ImageSampler,
    val scheduler: ImageScheduler,
    val threads: Int = 4,
    val previews: Boolean = steps > 1,
)

sealed class ImageGenEvent {
    /** Lazy weight loading inside the first generation, text encoding, or the initial model load. */
    data object Loading : ImageGenEvent()

    /**
     * One denoise step finished. [step] == 0 means sampling has started; [step] == [totalSteps] is the
     * last one. [previewPng] is present only when [ImageGenRequest.previews] is on and the model has a
     * known latent projection (SD1.x and SDXL do).
     */
    data class Progress(
        val step: Int,
        val totalSteps: Int,
        val elapsedMs: Long,
        val previewPng: ByteArray?,
    ) : ImageGenEvent()

    /** Sampling done, TAESD decode running (~4 s at 512 px). */
    data object Decoding : ImageGenEvent()

    data class Done(
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val seed: Long,
        val totalMs: Long,
    ) : ImageGenEvent()

    data class Error(val message: String) : ImageGenEvent()
}

sealed class ImageEngineState {
    data object Idle : ImageEngineState()
    data object Loading : ImageEngineState()
    data class Ready(val modelId: String) : ImageEngineState()
    data class Generating(val modelId: String) : ImageEngineState()
    data class Error(val message: String) : ImageEngineState()
}
