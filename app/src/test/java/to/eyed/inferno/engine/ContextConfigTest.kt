package to.eyed.inferno.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContextConfigTest {
    @Test fun defaultsEncodeToTheCtxpLayout() {
        val a = ContextConfig(nCtx = 8192).toNative()
        assertEquals(CtxP.SIZE, a.size)
        assertEquals(8192, a[CtxP.N_CTX]); assertEquals(512, a[CtxP.N_BATCH]); assertEquals(512, a[CtxP.N_UBATCH])
        assertEquals(4, a[CtxP.N_THREADS]); assertEquals(4, a[CtxP.N_THREADS_BATCH]); assertEquals(1, a[CtxP.BIG_CORES_ONLY])
        assertEquals(1, a[CtxP.FLASH_ATTN]); assertEquals(1, a[CtxP.TYPE_K]); assertEquals(1, a[CtxP.TYPE_V])
        assertEquals(1, a[CtxP.SWA_FULL]); assertEquals(50, a[CtxP.POLL]); assertEquals(8, a[CtxP.N_OUTPUTS_MAX])
    }

    @Test fun flashAttentionOffIsZeroNeverMinusOne() {
        assertEquals(0, ContextConfig(nCtx = 4096, flashAttention = false).toNative()[CtxP.FLASH_ATTN])
    }

    @Test fun quantizedKvForcesFlashAttentionAndGgmlTypes() {
        val q8 = ContextConfig(nCtx = 4096, kvType = KvCacheType.Q8_0, flashAttention = true).toNative()
        assertEquals(8, q8[CtxP.TYPE_K]); assertEquals(8, q8[CtxP.TYPE_V]); assertEquals(1, q8[CtxP.FLASH_ATTN])
        val q4 = ContextConfig(nCtx = 4096, kvType = KvCacheType.Q4_0).toNative()
        assertEquals(2, q4[CtxP.TYPE_K])
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4096, kvType = KvCacheType.Q8_0, flashAttention = false) }
    }

    @Test fun invariantsRejectNativeAssertInputs() {
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 1024) }              // < MIN_CTX
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4100) }              // not a multiple of 256
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4096, nBatch = 1024, nUbatch = 512) }
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4096, nBatch = 256, nUbatch = 256) }
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4096, nBatch = 600, nUbatch = 600) }
        assertThrows(IllegalArgumentException::class.java) { ContextConfig(nCtx = 4096, nOutputsMax = 0) }
        ContextConfig(nCtx = 2048, nBatch = 1088, nUbatch = 1088, swaFull = false, poll = 0)              // valid Gemma-style config
    }

    @Test fun memoryEstimateTotalsExcludeMappedWeightsAndTakeTheLargerEncoderFigure() {
        val e = MemoryEstimate(modelBytes = 3000, modelResidentBytes = 1400, modelMappedBytes = 1600, kvBytes = 300, computeBytes = 200,
            mmprojBytes = 500, clipComputeBytes = 100, encoderPeakBytes = 450, deviceTotal = 8000)
        assertEquals(500 + 450L, e.visionBytes)
        assertEquals(1400 + 300 + 200 + 950L, e.totalBytes)
    }

    @Test fun generationStatsDerivedRates() {
        val s = GenerationStats(promptTokens = 120, reusedTokens = 20, generatedTokens = 50, prefillMs = 1000, decodeMs = 2500,
            imageEncodeMs = 0, kvUsedTokens = 170, nCtx = 8192)
        assertEquals(20.0, s.decodeTps, 1e-9); assertEquals(100.0, s.prefillTps, 1e-9)
        assertEquals(170f / 8192, s.contextFraction, 1e-6f)
        assertEquals(0.0, s.copy(decodeMs = 0).decodeTps, 0.0)
    }

    @Test fun finishReasonMapping() {
        assertEquals(FinishReason.EOS, FinishReason.fromNative(NFinish.EOS)); assertEquals(FinishReason.LENGTH, FinishReason.fromNative(NFinish.LENGTH))
        assertEquals(FinishReason.CONTEXT_FULL, FinishReason.fromNative(NFinish.CONTEXT_FULL)); assertEquals(FinishReason.CANCELLED, FinishReason.fromNative(NFinish.CANCELLED))
        assertEquals(FinishReason.ERROR, FinishReason.fromNative(NFinish.ERROR)); assertEquals(FinishReason.ERROR, FinishReason.fromNative(NFinish.RUNNING))
    }
}
