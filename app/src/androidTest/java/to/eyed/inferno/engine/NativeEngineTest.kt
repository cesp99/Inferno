package to.eyed.inferno.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.inferno.engine.NativeTestSupport.TAG
import to.eyed.inferno.engine.NativeTestSupport.ctxParams
import to.eyed.inferno.engine.NativeTestSupport.modelFile
import to.eyed.inferno.engine.NativeTestSupport.msg
import to.eyed.inferno.engine.NativeTestSupport.runTurn
import kotlin.concurrent.thread

/**
 * WP1 acceptance (c)-(h), (k) on real models. Every native call happens on the instrumentation thread (the
 * engine-thread rule); only cancel() is issued from a helper thread. Models are read from
 * /data/local/tmp/inferno/models (world-readable) or files/models/<id>/.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    private var model = 0L
    private var ctx = 0L

    private val lfmText get() = modelFile("lfm2.5-vl-1.6b-q4_0", "LFM2.5-VL-1.6B-Q4_0.gguf")
    private val lfmMmproj get() = modelFile("lfm2.5-vl-1.6b-q4_0", "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf")

    private fun load(path: String, nCtx: Int = 4096, nBatch: Int = 512) {
        NativeTestSupport.ensureBackend()
        model = LlamaNative.modelLoad(path, null, true, 4, 0, 0) { done, total, phase ->
            if (done == total) Log.i(TAG, "load phase $phase done"); true
        }
        assertNotEquals(LlamaNative.lastErrorString(), 0L, model)
        ctx = LlamaNative.contextCreate(model, ctxParams(nCtx, nBatch))
        assertNotEquals(LlamaNative.lastErrorString(), 0L, ctx)
        NativeTestSupport.setGreedy(ctx)
    }

    @After
    fun tearDown() {
        if (ctx != 0L) LlamaNative.contextFree(ctx)
        if (model != 0L) LlamaNative.modelFree(model)
        ctx = 0L; model = 0L
        LlamaNative.estimateCacheClear()
    }

    // (c) text-only generation ------------------------------------------------------------------------
    @Test
    fun textTurnFinishesWithEos() {
        load(lfmText)
        assertTrue(!LlamaNative.mmprojLoaded(model))
        val nums = LlamaNative.modelInfoNumbers(model)
        val strs = LlamaNative.modelInfoStrings(model).map { LlamaNative.utf8(it) }
        Log.i(TAG, "model: arch=${strs[MInfoS.ARCH]} name=${strs[MInfoS.NAME]} template=${strs[MInfoS.TEMPLATE_NAME]} " +
            "params=${nums[MInfo.N_PARAMS]} ctxTrain=${nums[MInfo.N_CTX_TRAIN]} layers=${nums[MInfo.N_LAYER]}")
        assertEquals("lfm2", strs[MInfoS.ARCH])
        assertEquals(1L, nums[MInfo.TEMPLATE_SUPPORTED])
        assertEquals(4096, LlamaNative.contextNCtx(ctx))

        val t = runTurn(ctx, listOf(msg("user", "Say hello in five words.")), nPredict = 64)
        assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
        assertEquals(NFinish.EOS, t.finish)
        assertTrue(t.stats[GStat.GEN_TOKENS] > 0)
        assertTrue(t.text.isNotBlank())
        assertEquals(t.stats[GStat.KV_USED_TOKENS].toInt(), LlamaNative.kvUsedTokens(ctx))
    }

    // (e) prefix reuse over turns + regenerate (checkpoint restore path on a hybrid model) ------------------
    @Test
    fun prefixReuseAcrossTurnsAndRegenerate() {
        load(lfmText)
        val history = mutableListOf(msg("system", "You are terse."), msg("user", "Name one planet."))
        val t1 = runTurn(ctx, history, nPredict = 32)
        assertEquals(0, t1.rc)
        history += msg("assistant", t1.text.trim())
        history += msg("user", "Name another one.")
        val t2 = runTurn(ctx, history, nPredict = 32)
        assertEquals(0, t2.rc)
        assertTrue("reused ${t2.stats[GStat.REUSED_TOKENS]} < ${t1.stats[GStat.PROMPT_TOKENS]} - 1",
            t2.stats[GStat.REUSED_TOKENS] >= t1.stats[GStat.PROMPT_TOKENS] - 1)
        // regenerate: identical prompt; the generated suffix must be rolled back via seq_rm or a checkpoint
        val t3 = runTurn(ctx, history, nPredict = 32)
        assertEquals(0, t3.rc)
        assertTrue("regenerate reused ${t3.stats[GStat.REUSED_TOKENS]} (prompt ${t2.stats[GStat.PROMPT_TOKENS]})",
            t3.stats[GStat.REUSED_TOKENS] >= t2.stats[GStat.PROMPT_TOKENS] - 1)
        assertEquals(t2.text, t3.text)          // greedy => the regenerated answer is identical
    }

    @Test
    fun prefixReuseWithThinkingPrefixOnQwen35() {
        load(modelFile("qwen3.5-2b-q4_0", "Qwen3.5-2B-Q4_0.gguf"))
        val strs = LlamaNative.modelInfoStrings(model).map { LlamaNative.utf8(it) }
        Log.i(TAG, "qwen template=${strs[MInfoS.TEMPLATE_NAME]}")
        val history = mutableListOf(msg("user", "What is 2+2? Answer briefly."))
        var prev: NativeTestSupport.Turn? = null
        repeat(3) { i ->
            val t = runTurn(ctx, history, nPredict = 48, prefix = "<think>\n")
            assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
            val p = prev
            if (p != null) {
                // the whole previous prompt except the injected prefix tokens must come from the cache
                assertTrue("turn $i reused ${t.stats[GStat.REUSED_TOKENS]} prev prompt ${p.stats[GStat.PROMPT_TOKENS]}",
                    t.stats[GStat.REUSED_TOKENS] >= p.stats[GStat.PROMPT_TOKENS] - 3)
            }
            val visible = t.text.substringAfter("</think>", t.text).trim().ifEmpty { "ok" }
            history += msg("assistant", visible)
            history += msg("user", "And plus ${i + 1}? Answer briefly.")
            prev = t
        }
        val again = runTurn(ctx, history.dropLast(2), nPredict = 48, prefix = "<think>\n")   // regenerate last turn
        assertEquals(0, again.rc)
        assertTrue("regenerate reused ${again.stats[GStat.REUSED_TOKENS]}", again.stats[GStat.REUSED_TOKENS] >= prev!!.stats[GStat.PROMPT_TOKENS] - 3)
    }

    // (d) vision ------------------------------------------------------------------------------------------
    @Test
    fun imageTurnEncodesAndAnswers() {
        load(lfmText, nBatch = 1024)
        assertTrue(LlamaNative.lastErrorString(), LlamaNative.mmprojLoad(model, lfmMmproj, 4, 0, 0, null))
        assertTrue(LlamaNative.mmprojLoaded(model))
        assertEquals(1L, LlamaNative.modelInfoNumbers(model)[MInfo.HAS_VISION])
        val img = NativeTestSupport.loadImage(modelFile("lfm2.5-vl-1.6b-q4_0", "test-640.jpg"), 640)
        val messages = listOf(msg("user", "Describe this image in one sentence.", img.id))
        // token counting with a placeholder must agree with the real prompt
        val count = LlamaNative.promptTokenCount(ctx, messages.toTypedArray(), arrayOf(NativeImage(img.id, img.width, img.height, null)))
        assertTrue("count=$count", count > 64)
        var sawImagePhase = false
        val t = runTurn(ctx, messages, listOf(img), nPredict = 48) { _, _, phase -> if (phase == Phase.IMAGE) sawImagePhase = true; true }
        assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
        assertTrue(sawImagePhase)
        assertTrue(t.stats[GStat.IMAGE_ENCODE_MS] > 0)
        assertEquals(count.toDouble(), t.stats[GStat.PROMPT_TOKENS], 0.0)
        assertTrue(t.text, t.text.isNotBlank())
        // a second turn re-uses the image cells (encode skipped: no image time)
        val t2 = runTurn(ctx, messages + msg("assistant", t.text.trim()) + msg("user", "What colour dominates?"), listOf(img), nPredict = 24)
        assertEquals(0, t2.rc)
        assertEquals(0.0, t2.stats[GStat.IMAGE_ENCODE_MS], 0.0)
        assertTrue(t2.stats[GStat.REUSED_TOKENS] >= t.stats[GStat.PROMPT_TOKENS] - 1)
        LlamaNative.mmprojFree(model)
        assertTrue(!LlamaNative.mmprojLoaded(model))
        // images without a projector => -6
        assertEquals(-6, LlamaNative.generateStart(ctx, messages.toTypedArray(), arrayOf(img), 8, null, 0L, null))
    }

    @Test
    fun imageChunkLargerThanBatchIsRefused() {
        load(lfmText, nBatch = 64)
        assertTrue(LlamaNative.mmprojLoad(model, lfmMmproj, 4, 0, 0, null))
        val img = NativeTestSupport.loadImage(modelFile("lfm2.5-vl-1.6b-q4_0", "test-640.jpg"), 640)
        val rc = LlamaNative.generateStart(ctx, arrayOf(msg("user", "Describe.", img.id)), arrayOf(img), 8, null, 0L, null)
        assertEquals(LlamaNative.lastErrorString(), -4, rc)
    }

    // (f) cancellation ------------------------------------------------------------------------------------
    @Test
    fun cancelDuringPrefillReturnsFast() {
        load(lfmText, nCtx = 8192)
        val longText = buildString { repeat(330) { append("The quick brown fox jumps over the lazy dog number $it. ") } }
        val messages = arrayOf(msg("user", "$longText\nSummarise."))
        val count = LlamaNative.promptTokenCount(ctx, messages, emptyArray())
        assertTrue("prompt tokens $count", count >= 4000)
        val canceller = thread { Thread.sleep(300); LlamaNative.cancel() }
        val t0 = System.nanoTime()
        val rc = LlamaNative.generateStart(ctx, messages, emptyArray(), 8, null, 0L, null)
        val ms = (System.nanoTime() - t0) / 1e6
        canceller.join()
        Log.i(TAG, "cancel during prefill: rc=$rc after $ms ms")
        assertEquals(1, rc)
        assertEquals(NFinish.CANCELLED, LlamaNative.generateFinishReason(ctx))
        assertTrue("took $ms ms", ms < 1300.0)                       // 300 ms until cancel + < 1 s to return
        val t = runTurn(ctx, listOf(msg("user", "Say hi.")), nPredict = 8)   // the engine is usable again
        assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
    }

    @Test
    fun cancelDuringImageEncodeReturnsFast() {
        load(lfmText, nBatch = 1024)
        assertTrue(LlamaNative.mmprojLoad(model, lfmMmproj, 4, 0, 0, null))
        val img = NativeTestSupport.loadImage(modelFile("lfm2.5-vl-1.6b-q4_0", "test-640.jpg"), 1024)
        val messages = arrayOf(msg("user", "Describe.", img.id))
        var cancelAt = 0L
        val canceller = thread { Thread.sleep(600); cancelAt = System.nanoTime(); LlamaNative.cancel() }
        val rc = LlamaNative.generateStart(ctx, messages, arrayOf(img), 8, null, 0L, null)
        val after = System.nanoTime()
        canceller.join()
        val ms = (after - cancelAt) / 1e6
        Log.i(TAG, "cancel during image encode: rc=$rc, returned $ms ms after cancel()")
        assertEquals(1, rc)
        assertTrue("returned $ms ms after cancel", ms < 500.0)
        val t = runTurn(ctx, listOf(msg("user", "Say hi.")), nPredict = 8)   // the engine is usable again (text: 1024 px encodes take ~90 s)
        assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
    }

    // (g) bench ------------------------------------------------------------------------------------------
    @Test
    fun benchMeetsTargets() {
        load(lfmText)
        val r = LlamaNative.bench(ctx, 512, 128, 1) { d, t, _ -> Log.i(TAG, "bench $d/$t"); true }
        assertEquals(LlamaNative.lastErrorString(), 4, r.size)
        Log.i(TAG, "bench pp512=${r[0]} t/s tg128=${r[1]} t/s (pp ${r[2]} ms, tg ${r[3]} ms)")
        assertTrue("pp ${r[0]}", r[0] > 80.0)
        assertTrue("tg ${r[1]}", r[1] > 15.0)
    }

    // (h) memory estimate --------------------------------------------------------------------------------
    @Test
    fun estimateMemoryMatchesReference() {
        NativeTestSupport.ensureBackend()
        val qwen = modelFile("qwen3-vl-2b-q4_0", "Qwen3-VL-2B-Instruct-Q4_0.gguf")
        val qwenMm = modelFile("qwen3-vl-2b-q4_0", "mmproj-Qwen3-VL-2B-Q8_0.gguf")
        val p = ctxParams(16384, typeKv = 8)
        val t0 = System.nanoTime()
        val e = LlamaNative.estimateMemory(qwen, qwenMm, p)
        val firstMs = (System.nanoTime() - t0) / 1e6
        assertEquals(LlamaNative.lastErrorString(), MemEst.SIZE, e.size)
        Log.i(TAG, "qwen3-vl-2b @16k q8_0: model=${e[MemEst.MODEL] shr 20} MB (resident ${e[MemEst.MODEL_RESIDENT] shr 20}, mapped ${e[MemEst.MODEL_MAPPED] shr 20}) " +
            "kv=${e[MemEst.KV] shr 20} MB compute=${e[MemEst.COMPUTE] shr 20} MB mmproj=${e[MemEst.MMPROJ] shr 20} MB clip=${e[MemEst.CLIP_COMPUTE] shr 20} MB " +
            "total=${e[MemEst.DEVICE_TOTAL] shr 20} MB in $firstMs ms")
        val kvMiB = e[MemEst.KV] / 1048576.0
        assertTrue("kv $kvMiB MiB", kvMiB > 952 * 0.9 && kvMiB < 952 * 1.1)
        val fileBytes = java.io.File(qwen).length()
        // Tied-embedding models keep token_embd on the plain CPU buffer (GET_ROWS) and a repacked copy of the same
        // tensor as the output head, so the honest resident figure exceeds llama_model_size() by that tensor.
        assertTrue("model ${e[MemEst.MODEL]} vs file $fileBytes", e[MemEst.MODEL] > fileBytes * 0.9 && e[MemEst.MODEL] < fileBytes * 1.35)
        assertTrue(e[MemEst.MMPROJ] > 0 && e[MemEst.DEVICE_TOTAL] > 0)
        val t1 = System.nanoTime()
        val e2 = LlamaNative.estimateMemory(qwen, qwenMm, ctxParams(8192, typeKv = 8))
        val secondMs = (System.nanoTime() - t1) / 1e6
        Log.i(TAG, "second estimate in $secondMs ms, kv=${e2[MemEst.KV] shr 20} MB")
        assertTrue("second call $secondMs ms", secondMs < 300.0)
        assertTrue(e2[MemEst.KV] < e[MemEst.KV])

        val gemma = modelFile("gemma-4-e2b-q4_0", "gemma-4-E2B_q4_0-it.gguf")
        val g = LlamaNative.estimateMemory(gemma, null, ctxParams(8192, swaFull = false))
        assertEquals(LlamaNative.lastErrorString(), MemEst.SIZE, g.size)
        Log.i(TAG, "gemma4 e2b: model=${g[MemEst.MODEL] shr 20} MB mapped=${g[MemEst.MODEL_MAPPED] shr 20} MB resident=${g[MemEst.MODEL_RESIDENT] shr 20} MB kv=${g[MemEst.KV] shr 20} MB")
        assertTrue("mapped ${g[MemEst.MODEL_MAPPED]}", g[MemEst.MODEL_MAPPED] >= 1_000_000_000L)

        // the real load must agree with the estimate (buffer sizes are logged by load_tensors)
        load(qwen, nCtx = 16384)
        val real = LlamaNative.modelInfoNumbers(model)[MInfo.SIZE_BYTES]
        Log.i(TAG, "qwen3-vl-2b llama_model_size=${real shr 20} MB, estimate MODEL=${e[MemEst.MODEL] shr 20} MB")
    }

    // (k) thread pool rebuild ----------------------------------------------------------------------------
    @Test
    fun setThreadsRebuildsPoolsAndMask() {
        load(lfmText)
        val tids4 = LlamaNative.contextWorkerTids(ctx)
        Log.i(TAG, "tids(4 pinned)=${tids4.joinToString()} cpus=${tids4.map(NativeTestSupport::cpusAllowed)}")
        assertEquals(4, tids4.size)
        tids4.map(NativeTestSupport::cpusAllowed).forEach { assertEquals("4-7", it) }
        // cheap path (spec 4.2 a): only the counts shrink => llama_set_n_threads, pools untouched
        assertTrue(LlamaNative.contextSetThreads(ctx, 2, 2, true, 50))
        assertEquals(4, LlamaNative.contextWorkerTids(ctx).size)
        // mask/poll change => both pools rebuilt: 4 threads, unpinned
        assertTrue(LlamaNative.contextSetThreads(ctx, 4, 4, false, 0))
        val tidsAll = LlamaNative.contextWorkerTids(ctx)
        val cpusAll = tidsAll.map(NativeTestSupport::cpusAllowed)
        Log.i(TAG, "tids(4 unpinned)=${tidsAll.joinToString()} cpus=$cpusAll")
        assertEquals(4, tidsAll.size)
        cpusAll.forEach { assertEquals("0-7", it) }
        // pin again with 2 threads => rebuilt with a smaller pool and the big-core mask
        assertTrue(LlamaNative.contextSetThreads(ctx, 2, 2, true, 50))
        val tids2 = LlamaNative.contextWorkerTids(ctx)
        val cpus2 = tids2.map(NativeTestSupport::cpusAllowed)
        Log.i(TAG, "tids(2 pinned)=${tids2.joinToString()} cpus=$cpus2")
        assertEquals(2, tids2.size)
        cpus2.forEach { assertEquals("4-7", it) }
        val t = runTurn(ctx, listOf(msg("user", "Say hi.")), nPredict = 8)
        assertEquals(0, t.rc)
    }

    // (j) MiniCPM-V 4.6 peak RSS -------------------------------------------------------------------------
    @Test
    fun minicpmVisionPeakRss() {
        load(modelFile("minicpm-v-4.6-q4_0", "MiniCPM-V-4_6-Q4_0.gguf"), nCtx = 4096, nBatch = 1024)
        LlamaNative.modelSetTemplate(model, "chatml")
        assertTrue(LlamaNative.lastErrorString(), LlamaNative.mmprojLoad(model, modelFile("minicpm-v-4.6-q4_0", "mmproj-MiniCPM-V-4.6-Q8_0.gguf"), 4, 0, 0, null))
        val img = NativeTestSupport.loadImage(modelFile("minicpm-v-4.6-q4_0", "test-640.jpg"), 448)
        val t = runTurn(ctx, listOf(msg("user", "Describe this image in one sentence.", img.id)), listOf(img), nPredict = 32)
        assertEquals(LlamaNative.lastErrorString(), 0, t.rc)
        val peak = NativeTestSupport.peakRssBytes()
        Log.i(TAG, "minicpm-v 4.6 peak RSS = ${peak shr 20} MB, encode ${t.stats[GStat.IMAGE_ENCODE_MS]} ms, text='${t.text.take(80)}'")
        assertTrue("peak $peak", peak in 1..3_000_000_000L)
    }
}
