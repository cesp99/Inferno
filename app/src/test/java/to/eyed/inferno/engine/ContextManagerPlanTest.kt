package to.eyed.inferno.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.engine.EngineTestFixtures.GB
import to.eyed.inferno.engine.EngineTestFixtures.MB
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ModelFamily

/**
 * plan() over an analytic fake of estimateMemory (resident weights + KV that scales with nCtx and the KV type +
 * fixed compute/vision figures). Numbers mirror the Seeker: 8 GB device, ~3.9 GB free when idle.
 */
class ContextManagerPlanTest {
    private class AnalyticEngine(val residentBytes: Long = 1400 * MB, val kvPerTokenF16: Long = 12 * 1024, val computeBytes: Long = 300 * MB) : PlannerEngine {
        val probed = mutableListOf<Int>()
        override suspend fun estimateMemory(model: LocalModel, config: ContextConfig): MemoryEstimate {
            probed += config.nCtx
            val perElem = when (config.kvType) { KvCacheType.F16 -> 1.0; KvCacheType.Q8_0 -> 34.0 / 64; KvCacheType.Q4_0 -> 18.0 / 64 }
            val kv = (model.catalog?.kvBytesPerTokenF16?.toLong() ?: kvPerTokenF16) * config.nCtx * perElem
            return MemoryEstimate(
                modelBytes = residentBytes + 300 * MB, modelResidentBytes = residentBytes, modelMappedBytes = 300 * MB,
                kvBytes = kv.toLong(), computeBytes = computeBytes,
                mmprojBytes = if (model.hasVision) 500 * MB else 0, clipComputeBytes = if (model.hasVision) 322 * MB else 0,
                encoderPeakBytes = if (!model.hasVision) 0 else model.catalog?.encoderPeakBytes ?: ContextManager.IMPORT_ENCODER_PEAK_BYTES,
                deviceTotal = 8 * GB,
            )
        }
        override suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>): Int = error("unused")
    }

    private fun gate(avail: Long) = (ContextManager.BUDGET_GATE * ContextManager.budgetBytes(avail)).toLong()

    @Test fun autoGrowsAboveTheCatalogFloorWhenMemoryAllows() = runBlocking {
        // Qwen3.5-2B: 12 KB/token, floor 32k, train 262k, ~1.2 GB resident + 0.25 GB compute, Q8_0 mmproj 0.4 GB + 0.3 GB
        // encoder peak. Idle Seeker (3.9 GB free -> gate 2.9 GB) must resolve to >= 65,536 (the KV type flips to Q8_0 past ~56k f16).
        val eng = AnalyticEngine(residentBytes = 1200 * MB, computeBytes = 250 * MB)
        val model = EngineTestFixtures.local(EngineTestFixtures.catalog(encoderPeakBytes = 300 * MB))
        val plan = ContextManager(eng).plan(model, SettingsState(), EngineTestFixtures.cpu({ 3900 * MB }))
        assertNull(plan.reason); assertNull(plan.clampedFrom); assertTrue(plan.auto); assertTrue(plan.visionAllowed)
        assertTrue("nCtx=${plan.config.nCtx}", plan.config.nCtx >= 65536)
        assertEquals(0, plan.config.nCtx % ContextManager.CTX_STEP)
        assertTrue(plan.estimate.totalBytes <= gate(3900 * MB))
        assertTrue("probes=${eng.probed.size}", eng.probed.size <= ContextManager.MAX_PROBES + 2)
        assertTrue(plan.resolvedLabel.startsWith("Auto · ")); assertTrue(plan.resolvedLabel.endsWith(" GB"))
        assertEquals(512, plan.config.nBatch)               // vision model: max(512, BALANCED 512, imageTokensMax 512)
        assertEquals(KvCacheType.Q8_0, plan.config.kvType)
    }

    @Test fun autoNeverExceedsTheGateUnlessItReportsAReason() = runBlocking {
        for (avail in listOf(2200 * MB, 2600 * MB, 3000 * MB, 3900 * MB, 5000 * MB)) {
            val eng = AnalyticEngine()
            val plan = ContextManager(eng).plan(EngineTestFixtures.local(), SettingsState(), EngineTestFixtures.cpu({ avail }))
            val need = if (plan.visionAllowed) plan.estimate.totalBytes else plan.estimate.totalBytes - plan.estimate.visionBytes
            assertTrue("avail=$avail nCtx=${plan.config.nCtx} need=$need reason=${plan.reason}", plan.reason != null || need <= gate(avail))
        }
    }

    @Test fun visionIsDisabledRatherThanBlockingWhenOnlyTheVisionBytesDoNotFit() = runBlocking {
        // 1400 resident + 300 compute + 32k*12KB = 384 MB KV = 2.08 GB text-only; + vision 1.0 GB = 3.08 GB.
        // avail 3.0 GB -> gate 2.1 GB: vision does not fit at the floor, text-only does.
        val eng = AnalyticEngine()
        val plan = ContextManager(eng).plan(EngineTestFixtures.local(), SettingsState(), EngineTestFixtures.cpu({ 3000 * MB }))
        assertNull(plan.reason); assertFalse(plan.visionAllowed)
        assertTrue(plan.config.nCtx >= 32768)
        assertTrue(plan.estimate.totalBytes - plan.estimate.visionBytes <= gate(3000 * MB))
    }

    @Test fun explicitSizeIsClampedDownWithClampedFrom() = runBlocking {
        val eng = AnalyticEngine()
        // Explicit 131072 tokens: 1.5 GB of f16 KV -> AUTO picks Q8_0 (0.8 GB); total 1400+300+800+1000 = 3.5 GB > gate(3900 MB) = 2.9 GB.
        val plan = ContextManager(eng).plan(EngineTestFixtures.local(), SettingsState(contextSize = 131072), EngineTestFixtures.cpu({ 3900 * MB }))
        assertFalse(plan.auto)
        assertEquals(131072, plan.clampedFrom)
        assertTrue(plan.visionAllowed)                       // explicit sizes clamp first; vision is only dropped when MIN_CTX fails
        assertTrue(plan.config.nCtx < 131072); assertTrue(plan.config.nCtx >= ContextManager.MIN_CTX)
        assertTrue(plan.estimate.totalBytes <= gate(3900 * MB))
        assertTrue(plan.resolvedLabel.startsWith("Custom · "))
    }

    @Test fun explicitSizeThatFitsIsUsedAsIs() = runBlocking {
        val plan = ContextManager(AnalyticEngine()).plan(EngineTestFixtures.local(), SettingsState(contextSize = 16384), EngineTestFixtures.cpu({ 4200 * MB }))
        assertEquals(16384, plan.config.nCtx); assertNull(plan.clampedFrom); assertFalse(plan.auto)
    }

    @Test fun nothingFitsGivesAReasonWithResidentBytesOnly() = runBlocking {
        val plan = ContextManager(AnalyticEngine()).plan(EngineTestFixtures.local(), SettingsState(), EngineTestFixtures.cpu({ 1500 * MB }))
        assertNotNull(plan.reason)
        // 1400 resident + 24 MB KV @2k + 300 compute + 512 margin ~ 2.2 GB; the 300 MB mapped weights are not counted.
        assertTrue(plan.reason!!, Regex("Needs about 2\\.[12] GB free, 1\\.5 GB available").matches(plan.reason!!))
        assertEquals(ContextManager.MIN_CTX, plan.config.nCtx)
    }

    @Test fun kvTypeFollowsThePrefAndForcesFlashAttention() = runBlocking {
        val model = EngineTestFixtures.local(EngineTestFixtures.catalog(id = "qwen3-vl-2b-q4_0", family = ModelFamily.QWEN3VL, contextMax = 262144, defaultCtx = 16384, kvBytesPerTokenF16 = 112 * 1024))
        // 112 KB x 16k = 1.75 GB f16 > max(600 MB, 20 % of 3.4 GB) -> Q8_0 in AUTO, FA forced even when the toggle is off.
        val auto = ContextManager(AnalyticEngine()).plan(model, SettingsState(flashAttention = false), EngineTestFixtures.cpu({ 3900 * MB }))
        assertEquals(KvCacheType.Q8_0, auto.config.kvType); assertTrue(auto.config.flashAttention)
        val f16 = ContextManager(AnalyticEngine()).plan(model, SettingsState(kvCache = KvCachePref.F16, flashAttention = false, contextSize = 8192), EngineTestFixtures.cpu({ 3900 * MB }))
        assertEquals(KvCacheType.F16, f16.config.kvType); assertFalse(f16.config.flashAttention)
    }

    @Test fun gemmaFamiliesUseTheIswaCacheAndSettingsFlowIntoTheConfig() = runBlocking {
        val gemma = EngineTestFixtures.local(EngineTestFixtures.catalog(id = "gemma-4-e2b-q4_0", family = ModelFamily.GEMMA4, contextMax = 131072, defaultCtx = 16384, kvBytesPerTokenF16 = 20 * 1024, imageTokensMax = 560))
        val settings = SettingsState(threads = 3, pinBigCores = false, poll = 0, imageDetail = ImageDetail.HIGH)
        val plan = ContextManager(AnalyticEngine()).plan(gemma, settings, EngineTestFixtures.cpu({ 3900 * MB }))
        assertFalse(plan.config.swaFull)
        assertEquals(3, plan.config.nThreads); assertEquals(3, plan.config.nThreadsBatch); assertFalse(plan.config.bigCoresOnly); assertEquals(0, plan.config.poll)
        assertEquals(576, plan.config.nBatch)               // max(512, catalog 560) rounded to 64; HIGH's 1024 is not a token count any projector emits
        assertEquals(plan.config.nBatch, plan.config.nUbatch)
        val qwen = ContextManager(AnalyticEngine()).plan(EngineTestFixtures.local(), SettingsState(), EngineTestFixtures.cpu({ 3900 * MB }))
        assertTrue(qwen.config.swaFull)
    }

    @Test fun textOnlyModelsNeverCarryVisionBytes() = runBlocking {
        val model = EngineTestFixtures.local(EngineTestFixtures.catalog(vision = false), vision = false)
        val plan = ContextManager(AnalyticEngine()).plan(model, SettingsState(), EngineTestFixtures.cpu({ 3900 * MB }))
        assertTrue(plan.visionAllowed)                       // nothing to disable
        assertEquals(0L, plan.estimate.visionBytes); assertEquals(512, plan.config.nBatch)
    }

    @Test fun importsWithoutCatalogUseConservativeDefaults() = runBlocking {
        // No catalog and an unreadable GGUF path: 32k training default, conservative KV/token, 1024-token image batch.
        val model = EngineTestFixtures.local(null, vision = true)
        val plan = ContextManager(AnalyticEngine(kvPerTokenF16 = 64 * 1024)).plan(model, SettingsState(), EngineTestFixtures.cpu({ 3900 * MB }))
        assertNull(plan.reason)
        assertEquals(1024, plan.config.nBatch)
        assertTrue(plan.config.nCtx <= 32768)
        assertEquals(ContextManager.IMPORT_ENCODER_PEAK_BYTES, plan.estimate.encoderPeakBytes)
    }
}
