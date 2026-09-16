package to.eyed.inferno.sd

/**
 * JNI surface of libinferno_sd.so (stable-diffusion.cpp). Mirrors sd_jni.cpp exactly.
 *
 * Every function except [cancel], [isLoaded] and [lastError] must be called on the single
 * worker thread owned by [ImageEngine]; the native side keeps exactly one sd_ctx.
 */
object SdNative {
    init {
        System.loadLibrary("inferno_sd")
    }

    /** Stable codes shared with sd_engine.h (never the sd.cpp enum ordinals). */
    const val SAMPLER_EULER = 0
    const val SAMPLER_EULER_A = 1
    const val SAMPLER_LCM = 2
    const val SCHEDULER_DISCRETE = 0
    const val SCHEDULER_SGM_UNIFORM = 1

    const val STATUS_OK = 0L
    const val STATUS_CANCELLED = 1L
    const val STATUS_ERROR = 2L

    /** Called on the worker thread while [generate] blocks. Must not throw. */
    interface Listener {
        fun onProgress(step: Int, steps: Int, secPerStep: Float)

        /** Latent-projection preview at latent resolution (width/8 x height/8), RGBA8. */
        fun onPreview(step: Int, width: Int, height: Int, rgba: ByteArray)
    }

    @JvmStatic
    external fun systemInfo(): String

    /** Returns null on success, else the error message. Frees any previously loaded model. */
    @JvmStatic
    external fun load(modelPath: String, taesdPath: String, threads: Int, flashAttn: Boolean): String?

    @JvmStatic
    external fun unload()

    @JvmStatic
    external fun isLoaded(): Boolean

    /** Thread-safe. Aborts the running [generate] within a few hundred milliseconds. */
    @JvmStatic
    external fun cancel()

    @JvmStatic
    external fun lastError(): String

    /** Pins the calling thread to the [threads] fastest cores; ggml worker threads inherit it. */
    @JvmStatic
    external fun pinBigCores(threads: Int)

    /**
     * Blocking. Returns RGBA8 pixels (width*height*4) or null; [outInfo] (size >= 4) receives
     * [width, height, seed, status] with status one of [STATUS_OK], [STATUS_CANCELLED], [STATUS_ERROR].
     */
    @JvmStatic
    external fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        sampler: Int,
        scheduler: Int,
        previews: Boolean,
        listener: Listener?,
        outInfo: LongArray,
    ): ByteArray?
}
