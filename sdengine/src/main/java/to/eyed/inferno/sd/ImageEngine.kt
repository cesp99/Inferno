package to.eyed.inferno.sd

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Text-to-image engine: one stable-diffusion.cpp context at a time on one dedicated worker
 * thread pinned to the big cores.
 *
 * @param threads ggml thread count, fixed for the lifetime of a loaded model (sd.cpp has no
 *   per-request thread API). 4 = the A78 cluster on the Seeker; 8 (A55s included) is slower.
 * @param context Optional; when given, the memory watchdog reads `ActivityManager.availMem`,
 *   otherwise `/proc/meminfo` MemAvailable (same signal, no Context needed in tests).
 */
class ImageEngine(
    private val threads: Int = 4,
    private val context: Context? = null,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "inferno-sd") }
    private val dispatcher = executor.asCoroutineDispatcher()

    // Native work is launched here, deliberately NOT as a child of the collector's scope: a
    // cancelled collector must be able to wait for the native call to unwind (it cannot be
    // interrupted, only asked to stop through SdNative.cancel()).
    private val nativeScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guarantees at most one native job (load, generate, unload) at any time. */
    private val jobMutex = Mutex()

    private val _state = MutableStateFlow<ImageEngineState>(ImageEngineState.Idle)
    val state: StateFlow<ImageEngineState> = _state.asStateFlow()

    @Volatile
    private var loadedSpec: ImageModelSpec? = null

    suspend fun load(spec: ImageModelSpec, modelPath: String, taesdPath: String) {
        jobMutex.withLock {
            _state.value = ImageEngineState.Loading
            val err = try {
                withWatchdog(onLowMemory = { SdNative.cancel() }) {
                    withContext(dispatcher) {
                        // Flash attention in the UNet halves the compute buffer on CPU (docs/performance.md);
                        // the speed cost is negligible next to the memory headroom it buys on an 8 GB phone.
                        SdNative.load(modelPath, taesdPath, threads, true)
                    }
                }
            } catch (e: CancellationException) {
                // SdNative.load cannot be interrupted: by the time withContext rethrows it has either run to
                // completion or never started. Drop the orphaned sd_ctx (1.8-2.6 GB with no owner) and settle the
                // state instead of staying in Loading forever.
                withContext(NonCancellable + dispatcher) { SdNative.unload() }
                loadedSpec = null
                _state.value = ImageEngineState.Idle
                throw e
            }
            if (err == null && SdNative.isLoaded()) {
                loadedSpec = spec
                _state.value = ImageEngineState.Ready(spec.id)
            } else {
                loadedSpec = null
                withContext(dispatcher) { SdNative.unload() }
                _state.value = ImageEngineState.Error(err ?: "Not enough memory")
            }
        }
    }

    suspend fun unload() {
        jobMutex.withLock {
            withContext(dispatcher) { SdNative.unload() }
            loadedSpec = null
            _state.value = ImageEngineState.Idle
        }
    }

    /**
     * Cold flow. Emits [ImageGenEvent.Loading] immediately, then [ImageGenEvent.Progress] per denoise
     * step, [ImageGenEvent.Decoding], and exactly one of [ImageGenEvent.Done] / [ImageGenEvent.Error]
     * unless the collector cancels, in which case the native job is aborted (well under a second)
     * and joined before the flow completes.
     */
    fun generate(req: ImageGenRequest): Flow<ImageGenEvent> = channelFlow {
        val spec = loadedSpec
        if (spec == null || !SdNative.isLoaded()) {
            send(ImageGenEvent.Error("No image model loaded"))
            return@channelFlow
        }
        if (!jobMutex.tryLock()) {
            send(ImageGenEvent.Error("Image engine is busy"))
            return@channelFlow
        }
        _state.value = ImageEngineState.Generating(spec.id)
        val startNs = System.nanoTime()
        // Pick the seed here so Done can report it; sd.cpp would otherwise draw one we cannot read back.
        val seed = if (req.seed < 0) Random.nextLong(0, Int.MAX_VALUE.toLong()) else req.seed
        var lowMemory = false

        val listener = GenListener(this, startNs)
        var native: Deferred<NativeOutcome>? = null
        try {
            // Emitted before the native job exists so it can never trail the first Progress event.
            send(ImageGenEvent.Loading)
            val job = nativeScope.async {
                val info = LongArray(4)
                val rgba = SdNative.generate(
                    req.prompt, req.negativePrompt, req.width, req.height, req.steps, req.cfgScale, seed,
                    samplerCode(req.sampler), schedulerCode(req.scheduler), req.previews, listener, info,
                )
                NativeOutcome(rgba, info)
            }
            native = job
            val out = withWatchdog(onLowMemory = { lowMemory = true; SdNative.cancel() }) { job.await() }
            val totalMs = (System.nanoTime() - startNs) / 1_000_000
            when (out.info[3]) {
                SdNative.STATUS_OK -> {
                    val w = out.info[0].toInt()
                    val h = out.info[1].toInt()
                    val png = withContext(Dispatchers.Default) { encodePng(out.rgba!!, w, h, w, h) }
                    send(ImageGenEvent.Done(png, w, h, out.info[2], totalMs))
                }
                SdNative.STATUS_CANCELLED -> if (lowMemory) {
                    // Spec 12.3(b): cancel, unload, surface the error; keeping the weights around
                    // would only invite the LMK to kill the whole process next.
                    withContext(dispatcher) { SdNative.unload() }
                    loadedSpec = null
                    send(ImageGenEvent.Error("Not enough memory"))
                } else {
                    // A cancel that did not come from this collector (a stale native flag, a cancel() racing the
                    // start of the run): still exactly one terminal event, or the caller waits for a Done that
                    // never comes.
                    Log.w(TAG, "native job cancelled without a collector cancel")
                    send(ImageGenEvent.Error("Image generation was cancelled"))
                }
                else -> send(ImageGenEvent.Error(SdNative.lastError().ifEmpty { "Image generation failed" }))
            }
        } finally {
            val job = native
            if (job != null && !job.isCompleted) {
                SdNative.cancel()
                withContext(NonCancellable) { job.join() }
            }
            _state.value = loadedSpec?.let { ImageEngineState.Ready(it.id) } ?: ImageEngineState.Idle
            jobMutex.unlock()
        }
    }

    /**
     * Peak resident bytes for one [ImageModelSpec.nativeSize] image. Measured on the Seeker
     * (bench-seeker.md, sdcpp.md 5.2): SD1.5 q8_0 1.77 GB of weights peaks at 2.35 GB RSS, SDXL
     * Lightning q4_0 2.59 GB at ~3.0 GB, i.e. ~0.6 GB of TE/UNet/TAESD compute buffers at 512 px
     * that scale with the pixel count. Pass [modelBytes] (the GGUF size) when the file is on disk.
     */
    fun estimateBytes(spec: ImageModelSpec, modelBytes: Long = -1): Long {
        val weights = when {
            modelBytes > 0 -> modelBytes
            spec.arch == ImageArch.SD15 -> 1_800_000_000L
            else -> 2_600_000_000L
        }
        val px = spec.nativeSize.toLong() * spec.nativeSize
        val buffers = 600_000_000L * px / (512L * 512L)
        return weights + buffers
    }

    /** Releases the worker thread. The engine is unusable afterwards. */
    fun close() {
        nativeScope.launch { SdNative.unload() }
        executor.shutdown()
    }

    // ------------------------------------------------------------------------------------------

    private class NativeOutcome(val rgba: ByteArray?, val info: LongArray)

    /** Runs on the worker thread from inside SdNative.generate; forwards into the channel. */
    private inner class GenListener(
        private val scope: ProducerScope<ImageGenEvent>,
        private val startNs: Long,
    ) : SdNative.Listener {
        // sd.cpp emits the (denoised) preview of a step right before that step's progress report.
        private var pendingPreview: ByteArray? = null

        override fun onProgress(step: Int, steps: Int, secPerStep: Float) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            scope.trySend(ImageGenEvent.Progress(step, steps, elapsedMs, pendingPreview))
            pendingPreview = null
            if (steps > 0 && step >= steps) scope.trySend(ImageGenEvent.Decoding)
        }

        override fun onPreview(step: Int, width: Int, height: Int, rgba: ByteArray) {
            // Runs on the sampler thread between steps: encode at the latent size (64x64, a few KB, sub-millisecond)
            // and let the UI crop + blur it; upscaling to the output size here would stall the diffusion loop for
            // ~100-200 ms per step and hand the main thread a 200 KB PNG to decode.
            pendingPreview = try {
                encodePng(rgba, width, height, width, height)
            } catch (e: Throwable) {
                Log.w(TAG, "preview encode failed", e)
                null
            }
        }
    }

    /**
     * Polls available memory every 500 ms while [block] runs and calls [onLowMemory] once when it
     * drops under [LOW_MEMORY_BYTES]; the native job is expected to return CANCELLED shortly after.
     */
    private suspend fun <T> withWatchdog(onLowMemory: () -> Unit, block: suspend () -> T): T {
        val watchdog = nativeScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500)
                val avail = availableMemoryBytes()
                if (avail in 1 until LOW_MEMORY_BYTES) {
                    Log.w(TAG, "memory watchdog: availMem=${avail / 1_000_000} MB, cancelling")
                    onLowMemory()
                    break
                }
            }
        }
        try {
            return block()
        } finally {
            watchdog.cancel()
        }
    }

    private fun availableMemoryBytes(): Long {
        val am = context?.getSystemService(ActivityManager::class.java)
        if (am != null) {
            return ActivityManager.MemoryInfo().also(am::getMemoryInfo).availMem
        }
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024) ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        const val TAG = "ImageEngine"
        const val LOW_MEMORY_BYTES = 500L * 1024 * 1024

        fun samplerCode(s: ImageSampler) = when (s) {
            ImageSampler.EULER -> SdNative.SAMPLER_EULER
            ImageSampler.EULER_A -> SdNative.SAMPLER_EULER_A
            ImageSampler.LCM -> SdNative.SAMPLER_LCM
        }

        fun schedulerCode(s: ImageScheduler) = when (s) {
            ImageScheduler.DISCRETE -> SdNative.SCHEDULER_DISCRETE
            ImageScheduler.SGM_UNIFORM -> SdNative.SCHEDULER_SGM_UNIFORM
        }

        /** RGBA8 -> PNG via the platform encoder; scaled when the target differs (latent previews). */
        fun encodePng(rgba: ByteArray, width: Int, height: Int, outWidth: Int, outHeight: Int): ByteArray {
            var bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            if (outWidth != width || outHeight != height) {
                val scaled = Bitmap.createScaledBitmap(bmp, outWidth, outHeight, true)
                bmp.recycle()
                bmp = scaled
            }
            val bos = ByteArrayOutputStream(outWidth * outHeight / 2)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            return bos.toByteArray()
        }
    }
}
