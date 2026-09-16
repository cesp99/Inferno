package to.eyed.inferno.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import java.io.File
import java.security.MessageDigest

/**
 * Shared helpers for the native instrumented tests. Model files are looked up in the app's files dir
 * (`files/models/<dir>/<name>`, populated with `adb shell run-as to.eyed.inferno cp ...`) and, as a fallback,
 * read directly from /data/local/tmp/inferno/models when that directory is readable by the app uid.
 */
object NativeTestSupport {
    const val TAG = "Inferno/NativeTest"
    private const val PHONE_DIR = "/data/local/tmp/inferno/models"

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Volatile private var initialised = false

    /** backendInit once per process, pinned to the big cores reported by cpuTopology(). */
    fun ensureBackend() {
        if (initialised) return
        val topo = LlamaNative.cpuTopology()
        LlamaNative.backendInit(Log.INFO, topo[Cpu.BIG_MASK])
        initialised = true
    }

    /** Returns the path of a model file or skips the test when it is not available on this device. */
    fun modelFile(dir: String, name: String): String {
        val local = File(context.filesDir, "models/$dir/$name")
        if (local.isFile && local.canRead()) return local.absolutePath
        val phone = File(PHONE_DIR, name)
        if (phone.isFile && phone.canRead()) return phone.absolutePath
        Assume.assumeTrue("model $name not available (push it with run-as cp into files/models/$dir/)", false)
        error("unreachable")
    }

    fun ctxParams(nCtx: Int, nBatch: Int = 512, threads: Int = 4, typeKv: Int = 1, flashAttn: Int = 1,
                  pin: Boolean = true, poll: Int = 50, swaFull: Boolean = true): IntArray {
        val p = IntArray(CtxP.SIZE)
        p[CtxP.N_CTX] = nCtx; p[CtxP.N_BATCH] = nBatch; p[CtxP.N_UBATCH] = nBatch
        p[CtxP.N_THREADS] = threads; p[CtxP.N_THREADS_BATCH] = threads
        p[CtxP.BIG_CORES_ONLY] = if (pin) 1 else 0; p[CtxP.FLASH_ATTN] = flashAttn
        p[CtxP.TYPE_K] = typeKv; p[CtxP.TYPE_V] = typeKv
        p[CtxP.SWA_FULL] = if (swaFull) 1 else 0; p[CtxP.POLL] = poll; p[CtxP.N_OUTPUTS_MAX] = 8
        return p
    }

    fun msg(role: String, text: String, vararg imageIds: String) =
        NativeChatMessage(role, text.toByteArray(Charsets.UTF_8), arrayOf(*imageIds))

    /** Greedy sampling so the tests are deterministic. */
    fun setGreedy(ctx: Long) {
        val f = FloatArray(SmpF.SIZE); f[SmpF.TEMP] = 0f; f[SmpF.TOP_P] = 1f; f[SmpF.TYPICAL_P] = 1f
        f[SmpF.REPEAT_PENALTY] = 1f; f[SmpF.DRY_BASE] = 1.75f
        val i = IntArray(SmpI.SIZE); i[SmpI.TOP_K] = 40; i[SmpI.REPEAT_LAST_N] = 64; i[SmpI.DRY_ALLOWED_LENGTH] = 2
        i[SmpI.DRY_PENALTY_LAST_N] = -1; i[SmpI.SEED] = 42
        check(LlamaNative.samplerSet(ctx, f, i, null)) { LlamaNative.lastErrorString() }
    }

    class Turn(val rc: Int, val text: String, val finish: Int, val stats: DoubleArray)

    /** generateStart + pull every token; returns the decoded text and the stats. */
    fun runTurn(ctx: Long, messages: List<NativeChatMessage>, images: List<NativeImage> = emptyList(),
                nPredict: Int = 64, prefix: String? = null, progress: ProgressCallback? = null): Turn {
        val rc = LlamaNative.generateStart(ctx, messages.toTypedArray(), images.toTypedArray(), nPredict,
            prefix?.toByteArray(Charsets.UTF_8), 0L, progress)
        val sb = StringBuilder()
        if (rc == 0) {
            while (true) {
                val piece = LlamaNative.generateNext(ctx) ?: break
                sb.append(LlamaNative.utf8(piece))
            }
        }
        val stats = LlamaNative.generateStats(ctx)
        val finish = LlamaNative.generateFinishReason(ctx)
        Log.i(TAG, "turn rc=$rc finish=$finish prompt=${stats[GStat.PROMPT_TOKENS]} reused=${stats[GStat.REUSED_TOKENS]} " +
            "gen=${stats[GStat.GEN_TOKENS]} prefill=${stats[GStat.PREFILL_MS]}ms decode=${stats[GStat.DECODE_MS]}ms " +
            "image=${stats[GStat.IMAGE_ENCODE_MS]}ms text='${sb.take(120)}'")
        return Turn(rc, sb.toString(), finish, stats)
    }

    /** Decodes a JPEG, scales the long edge to [maxEdge] and packs it as RGB888 with a sha256 id. */
    fun loadImage(path: String, maxEdge: Int): NativeImage {
        val src = BitmapFactory.decodeFile(path) ?: error("cannot decode $path")
        val scale = maxEdge.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f || scale > 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16 and 0xff).toByte(); rgb[i * 3 + 1] = (c shr 8 and 0xff).toByte(); rgb[i * 3 + 2] = (c and 0xff).toByte()
        }
        val id = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it) }
        return NativeImage(id, w, h, rgb)
    }

    /** Peak resident set size of this process in bytes (VmHWM). */
    fun peakRssBytes(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLong() * 1024
    }

    fun cpusAllowed(tid: Int): String =
        File("/proc/self/task/$tid/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(":")?.trim() ?: "?"
}
