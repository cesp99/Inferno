package to.eyed.inferno.sd

/** JNI surface of libinferno_sd.so (stable-diffusion.cpp). Placeholder until the engine lands. */
object SdNative {
    init {
        System.loadLibrary("inferno_sd")
    }

    @JvmStatic
    external fun systemInfo(): String
}
