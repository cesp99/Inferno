package to.eyed.inferno.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.engine.EngineTestFixtures.GB
import to.eyed.inferno.engine.EngineTestFixtures.MB
import to.eyed.inferno.models.ImageDetail

class KvTypeForTest {
    private val budget = 3400 * MB   // idle Seeker: ~3.9 GB avail - 512 MB

    @Test fun autoKeepsF16ForHybridModelsAt32k() {
        // LFM2.5 / Qwen3.5: 6 attention layers, 32k f16 KV ~ 384 MB < max(600 MB, 20 % of budget)
        assertEquals(KvCacheType.F16, ContextManager.kvTypeFor(KvCachePref.AUTO, 384 * MB, budget))
    }

    @Test fun autoSwitchesToQ8ForDenseVisionModelsAt16k() {
        // Qwen3-VL-2B: 112 KB/token x 16k = 1.8 GB
        assertEquals(KvCacheType.Q8_0, ContextManager.kvTypeFor(KvCachePref.AUTO, 1800 * MB, budget))
    }

    @Test fun autoThresholdIsTheLargerOf600MbAndTwentyPercent() {
        assertEquals(KvCacheType.F16, ContextManager.kvTypeFor(KvCachePref.AUTO, 650 * MB, budget))    // 20 % of 3.4 GB = 680 MB
        assertEquals(KvCacheType.Q8_0, ContextManager.kvTypeFor(KvCachePref.AUTO, 700 * MB, budget))
        assertEquals(KvCacheType.Q8_0, ContextManager.kvTypeFor(KvCachePref.AUTO, 601 * MB, 1 * GB))  // small budget: 600 MB floor wins
    }

    @Test fun explicitPrefsAreHonoured() {
        assertEquals(KvCacheType.F16, ContextManager.kvTypeFor(KvCachePref.F16, 5 * GB, budget))
        assertEquals(KvCacheType.Q8_0, ContextManager.kvTypeFor(KvCachePref.Q8_0, 1, budget))
        assertEquals(KvCacheType.Q4_0, ContextManager.kvTypeFor(KvCachePref.Q4_0, 1, budget))
    }

    @Test fun budgetAndBatchHelpers() {
        assertEquals(3400 * MB, ContextManager.budgetBytes(3912 * MB))
        assertEquals("<_media_> x", ContextManager.sanitize("<__media__> x"))
        assertEquals(512, ContextManager.batchFor(EngineTestFixtures.local(EngineTestFixtures.catalog(vision = false), vision = false), ImageDetail.HIGH))
        // Vision: max(512, detail.maxTokens, catalog.imageTokensMax) rounded up to 64.
        assertEquals(1024, ContextManager.batchFor(EngineTestFixtures.local(EngineTestFixtures.catalog(imageTokensMax = 512)), ImageDetail.HIGH))
        assertEquals(1088, ContextManager.batchFor(EngineTestFixtures.local(EngineTestFixtures.catalog(imageTokensMax = 1030)), ImageDetail.FAST))
        assertEquals(1024, ContextManager.batchFor(EngineTestFixtures.local(null, vision = true), ImageDetail.BALANCED))  // import with mmproj: conservative 1024
    }

    @Test fun analyticKvMatchesTheF16FormulaAndScalesForQuantTypes() {
        val cm = ContextManager(object : PlannerEngine {
            override suspend fun estimateMemory(model: to.eyed.inferno.models.LocalModel, config: ContextConfig) = error("unused")
            override suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>) = error("unused")
        })
        // Qwen3-VL-2B: 28 layers, 8 kv heads x 128 = 1024 per K and V => 112 KB/token f16
        val f16 = cm.kvBytesAnalytic(16384, 28, 1024, 1024, KvCacheType.F16)
        assertEquals(16384L * 28 * 2048 * 2, f16)
        val q8 = cm.kvBytesAnalytic(16384, 28, 1024, 1024, KvCacheType.Q8_0)
        assertEquals(f16 * 34.0 / 32 / 2, q8.toDouble(), f16 / 100.0)
        val q4 = cm.kvBytesAnalytic(16384, 28, 1024, 1024, KvCacheType.Q4_0)
        assertEquals(f16 * 18.0 / 32 / 2, q4.toDouble(), f16 / 100.0)
    }

    @Test fun reserveRule() {
        val cm = ContextManager(object : PlannerEngine {
            override suspend fun estimateMemory(model: to.eyed.inferno.models.LocalModel, config: ContextConfig) = error("unused")
            override suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>) = error("unused")
        })
        assertEquals(512, cm.reserveFor(GenerationParams(), 32768, thinkingOn = false))
        assertEquals(4096, cm.reserveFor(GenerationParams(), 65536, thinkingOn = true))   // 65536/8 = 8192 clamped to 4096
        assertEquals(1024, cm.reserveFor(GenerationParams(), 4096, thinkingOn = true))    // 512 clamped up to 1024
        assertEquals(2000, cm.reserveFor(GenerationParams(maxTokens = 2000), 65536, thinkingOn = true))
    }
}
