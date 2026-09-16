package to.eyed.inferno.sd

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test of the image engine with the smallest checkpoint on the Seeker.
 *
 * Model files are expected in `files/sd/` of the test package. Push them once with
 * ```
 * adb shell run-as to.eyed.inferno.sd.test sh -c 'mkdir -p files/sd && \
 *   cp /data/local/tmp/inferno/sd/sdxs-512-tinySDdistilled_Q8_0.gguf files/sd/ && \
 *   cp /data/local/tmp/inferno/sd/taesd_full.safetensors files/sd/'
 * ```
 * (the test also tries to copy them itself, which works when SELinux lets the app read
 * `/data/local/tmp`). Without the files the tests are skipped, not failed.
 */
@RunWith(AndroidJUnit4::class)
class ImageEngineTest {
    private val spec = ImageModelSpec(
        id = "sdxs-512-q8_0",
        displayName = "SDXS 512",
        arch = ImageArch.SD15,
        textFile = "sdxs-512-tinySDdistilled_Q8_0.gguf",
        taesdFile = "taesd_full.safetensors",
        steps = 1,
        cfgScale = 1f,
        sampler = ImageSampler.EULER,
        scheduler = ImageScheduler.DISCRETE,
        estSeconds = 12,
    )

    @Test
    fun generates256pxImageInOneStep() = runBlocking {
        val (engine, model, taesd) = setUp()
        try {
            engine.load(spec, model.path, taesd.path)
            assertEquals(ImageEngineState.Ready(spec.id), engine.state.value)

            val events = withTimeout(120_000) {
                engine.generate(
                    ImageGenRequest(
                        prompt = "a photo of a red fox in a snowy forest",
                        width = 256, height = 256, steps = 1, cfgScale = 1f, seed = 42,
                        sampler = ImageSampler.EULER, scheduler = ImageScheduler.DISCRETE,
                    ),
                ).toList()
            }
            val done = events.filterIsInstance<ImageGenEvent.Done>()
            assertEquals("events: $events", 1, done.size)
            assertTrue(events.none { it is ImageGenEvent.Error })
            assertTrue(events.any { it is ImageGenEvent.Progress })
            val png = done.single().png
            assertTrue("png too small: ${png.size}", png.size > 1024)
            assertEquals(0x89.toByte(), png[0])
            assertEquals('P'.code.toByte(), png[1])
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(256, bmp.width)
            assertEquals(256, bmp.height)
            assertEquals(42L, done.single().seed)
            assertEquals(ImageEngineState.Ready(spec.id), engine.state.value)
        } finally {
            engine.unload()
            engine.close()
        }
    }

    @Test
    fun cancellingTheCollectorAbortsTheNativeJobQuickly() = runBlocking {
        val (engine, model, taesd) = setUp()
        try {
            engine.load(spec, model.path, taesd.path)
            val job = launch {
                engine.generate(
                    ImageGenRequest(
                        prompt = "a castle on a hill", width = 512, height = 512, steps = 4, cfgScale = 1f,
                        seed = 1, sampler = ImageSampler.EULER, scheduler = ImageScheduler.DISCRETE,
                    ),
                ).collect()
            }
            // Let the text encoder finish and the UNet start; then abort mid-step.
            delay(6_000)
            val t0 = System.nanoTime()
            job.cancel()
            job.join()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel took $ms ms", ms < 3_000)
            assertEquals(ImageEngineState.Ready(spec.id), engine.state.value)
        } finally {
            engine.unload()
            engine.close()
        }
    }

    private data class Fixture(val engine: ImageEngine, val model: File, val taesd: File)

    private fun setUp(): Fixture {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "sd").apply { mkdirs() }
        val model = ensureFile(dir, spec.textFile)
        val taesd = ensureFile(dir, spec.taesdFile)
        assumeTrue("model files missing in ${dir.path}; see class doc", model != null && taesd != null)
        return Fixture(ImageEngine(threads = 4, context = ctx), model!!, taesd!!)
    }

    private fun ensureFile(dir: File, name: String): File? {
        val dst = File(dir, name)
        if (dst.exists() && dst.length() > 0) return dst
        val src = File("/data/local/tmp/inferno/sd/$name")
        return try {
            if (src.canRead()) src.copyTo(dst, overwrite = true) else null
        } catch (e: Exception) {
            null
        }
    }
}
