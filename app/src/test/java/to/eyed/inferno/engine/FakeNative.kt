package to.eyed.inferno.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted stand-in for libinferno.so: records every call, hands out fake handles and streams [pieces] from
 * generateNext. [blockUntilCancel] makes generateNext hang (as a real decode would) until [cancel] flips the flag,
 * which is how the collector-cancellation and watchdog paths are exercised on the host JVM.
 */
internal class FakeNative : NativeApi {
    val calls = CopyOnWriteArrayList<String>()
    val cancelled = AtomicBoolean(false)
    val cancelCount = AtomicInteger(0)

    var pieces: List<String> = listOf("Hello", " world")
    var blockUntilCancel = false
    var startRc = 0
    var finish = NFinish.EOS
    var errorText = "native says no"
    var estimate = longArrayOf(1500L shl 20, 300L shl 20, 200L shl 20, 500L shl 20, 8L shl 30, 1400L shl 20, 100L shl 20, 120L shl 20)
    var modelLoadResult = 1L
    var contextCreateResult = 2L
    var nCtx = 8192
    var mmprojLoadResult = true
    var mmproj = false
    var prefillTotal = 40
    var stats = doubleArrayOf(40.0, 10.0, 2.0, 120.0, 100.0, 0.0, 42.0, 42.0)
    var lastPrefix: ByteArray? = null
    var lastAvailMem = 0L
    var lastMessages: Array<NativeChatMessage> = emptyArray()
    var lastImages: Array<NativeImage> = emptyArray()
    var lastThreads: IntArray? = null
    var promptTokens: (Array<NativeChatMessage>, Array<NativeImage>) -> Int = { m, i -> m.sumOf { it.content.size / 4 } + i.size * 256 }
    var contextCreated = 0
    var loadProgressSteps = 0

    private var next = 0

    override fun backendInit(minLogPriority: Int, bigMask: Int, gpuPolicy: Int, cacheDir: String) { calls += "backendInit($bigMask)" }
    override fun systemInfo(): String = "fake"
    override fun modelLoad(path: String, mmprojPath: String?, useMmap: Boolean, nThreadsMmproj: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Long {
        calls += "modelLoad($path,mmap=$useMmap,maxTok=$imageMaxTokens)"
        for (i in 1..loadProgressSteps) if (progress?.onProgress(i * 1000 / loadProgressSteps, 1000, Phase.LOAD) == false) return 0L
        return modelLoadResult
    }
    override fun modelFree(model: Long) { calls += "modelFree" }
    override fun modelInfoNumbers(model: Long): LongArray = LongArray(MInfo.SIZE).also {
        it[MInfo.N_PARAMS] = 2_000_000_000; it[MInfo.SIZE_BYTES] = 1500L shl 20; it[MInfo.N_CTX_TRAIN] = 262144; it[MInfo.N_LAYER] = 28
        it[MInfo.N_EMBD] = 2048; it[MInfo.N_HEAD_KV] = 8; it[MInfo.N_SWA] = 0; it[MInfo.HAS_VISION] = 0; it[MInfo.TEMPLATE_SUPPORTED] = 1; it[MInfo.N_VOCAB] = 151936
    }
    override fun modelInfoStrings(model: Long): Array<ByteArray> =
        arrayOf("qwen35".toByteArray(), "Qwen3.5 2B 🦉".toByteArray(), "qwen".toByteArray(), templateName.toByteArray())
    var templateName = "chatml"
    override fun mmprojLoad(model: Long, mmprojPath: String, nThreads: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Boolean {
        calls += "mmprojLoad(threads=$nThreads,maxTok=$imageMaxTokens)"
        mmproj = mmprojLoadResult
        return mmprojLoadResult
    }
    override fun mmprojFree(model: Long) { calls += "mmprojFree"; mmproj = false }
    override fun mmprojLoaded(model: Long): Boolean = mmproj
    override fun contextCreate(model: Long, params: IntArray): Long { calls += "contextCreate(nCtx=${params[CtxP.N_CTX]},threads=${params[CtxP.N_THREADS]})"; contextCreated++; return contextCreateResult }
    override fun contextFree(ctx: Long) { calls += "contextFree" }
    override fun contextNCtx(ctx: Long): Int = nCtx
    override fun contextSetThreads(ctx: Long, nGen: Int, nBatch: Int, bigCoresOnly: Boolean, poll: Int): Boolean {
        calls += "setThreads($nGen,$nBatch,$bigCoresOnly,$poll)"; lastThreads = intArrayOf(nGen, nBatch); return true
    }
    override fun estimateMemory(modelPath: String, mmprojPath: String?, params: IntArray): LongArray { calls += "estimate(${params[CtxP.N_CTX]})"; return estimate }
    override fun estimateCacheClear() { calls += "estimateCacheClear" }
    override fun samplerSet(ctx: Long, f: FloatArray, i: IntArray, grammar: String?): Boolean { calls += "samplerSet"; return true }
    override fun promptTokenCount(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>): Int = promptTokens(messages, images)
    override fun generateStart(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>, nPredict: Int,
                               assistantPrefixUtf8: ByteArray?, availMemBytes: Long, progress: ProgressCallback?): Int {
        calls += "generateStart(nPredict=$nPredict,images=${images.size})"
        cancelled.set(false); next = 0
        lastPrefix = assistantPrefixUtf8; lastAvailMem = availMemBytes; lastMessages = messages; lastImages = images
        if (startRc != 0) return startRc
        for (done in listOf(prefillTotal / 2, prefillTotal)) {
            if (progress?.onProgress(done, prefillTotal, if (images.isNotEmpty() && done < prefillTotal) Phase.IMAGE else Phase.TEXT) == false) return 1
            if (cancelled.get()) return 1
        }
        return 0
    }
    override fun generateNext(ctx: Long): ByteArray? {
        if (blockUntilCancel && next >= pieces.size) {
            while (!cancelled.get()) Thread.sleep(5)
            return null
        }
        if (cancelled.get() || next >= pieces.size) return null
        return pieces[next++].toByteArray()
    }
    override fun generateFinishReason(ctx: Long): Int = if (cancelled.get()) NFinish.CANCELLED else finish
    override fun generateStats(ctx: Long): DoubleArray = stats
    override fun cancel() { cancelCount.incrementAndGet(); cancelled.set(true); calls += "cancel" }
    override fun kvClear(ctx: Long) { calls += "kvClear" }
    override fun kvUsedTokens(ctx: Long): Int = stats[GStat.KV_USED_TOKENS].toInt()
    override fun bench(ctx: Long, nPrompt: Int, nGen: Int, reps: Int, progress: ProgressCallback?): DoubleArray {
        calls += "bench($nPrompt,$nGen,$reps)"
        cancelled.set(false)
        for (r in 1..reps) {
            if (blockUntilCancel) while (!cancelled.get()) Thread.sleep(5)
            if (cancelled.get() || progress?.onProgress(r, reps, Phase.BENCH) == false) return DoubleArray(0)
        }
        return doubleArrayOf(150.0, 20.0, 3400.0, 6400.0)
    }
    override fun lastErrorString(): String = errorText
}
