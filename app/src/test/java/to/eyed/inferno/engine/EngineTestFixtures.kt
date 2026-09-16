package to.eyed.inferno.engine

import to.eyed.inferno.models.CatalogModel
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ModelFamily
import to.eyed.inferno.models.ModelFileSpec
import to.eyed.inferno.models.ModelTier
import to.eyed.inferno.models.Quant
import to.eyed.inferno.models.SamplingDefaults
import to.eyed.inferno.models.ThinkingSpec

/** Catalog rows shaped like the real ones (numbers from the real catalog) for the planner and engine tests. */
object EngineTestFixtures {
    const val MB = 1024L * 1024
    const val GB = 1024L * MB

    val qwenThinking = ThinkingSpec(openTag = "<think>", closeTag = "</think>", defaultOn = false,
        disablePrefix = "<think>\n\n</think>\n\n", enablePrefix = "<think>\n")

    fun catalog(
        id: String = "qwen3.5-2b-q4_0", family: ModelFamily = ModelFamily.QWEN35, contextMax: Int = 262144, defaultCtx: Int = 32768,
        kvBytesPerTokenF16: Int = 12 * 1024, vision: Boolean = true, imageTokensMax: Int = 512, dynamicResolution: Boolean = true,
        maxImageEdgePx: Int = 1024, encoderPeakBytes: Long = 500 * MB, thinking: ThinkingSpec = qwenThinking, estTgTps: String = "14-16",
    ) = CatalogModel(
        id = id, displayName = id, family = family, tier = ModelTier.BALANCED, paramsB = 2f, license = "Apache-2.0",
        contextMax = contextMax, defaultCtx = defaultCtx, kvBytesPerTokenF16 = kvBytesPerTokenF16,
        text = ModelFileSpec("$id.gguf", "https://example.invalid/$id.gguf", 1_500 * MB, null, Quant.Q4_0),
        mmproj = if (vision) ModelFileSpec("mmproj-$id.gguf", "https://example.invalid/mmproj.gguf", 500 * MB, null, Quant.Q8_0) else null,
        thinking = thinking, sampling = SamplingDefaults(0.7f, 0.8f, 20, 0.0f, 1.0f, 1.5f),
        imageTokensMin = 0, imageTokensMax = imageTokensMax, dynamicResolution = dynamicResolution, maxImageEdgePx = maxImageEdgePx,
        encoderPeakBytes = encoderPeakBytes, estTgTps = estTgTps, blurb = "test", recommended = true,
    )

    fun local(catalog: CatalogModel? = catalog(), vision: Boolean = catalog?.mmproj != null, id: String = catalog?.id ?: "import-abcd1234") =
        LocalModel(
            id = id, displayName = id, textPath = "/models/$id/text.gguf",
            mmprojPath = if (vision) "/models/$id/mmproj.gguf" else null,
            textBytes = 1_500 * MB, mmprojBytes = if (vision) 500 * MB else 0, quant = Quant.Q4_0, catalog = catalog, importedAt = 0,
        )

    fun cpu(availBytes: () -> Long, totalBytes: Long = 8 * GB) = CpuTopology(
        CpuTopology.Probe(nCores = 8, nBig = 4, bigMask = 0xF0, hasDotprod = true, hasFp16 = true, hasI8mm = false, hasSve = false),
        socName = "test", totalRamBytes = totalBytes, availRam = availBytes,
    )
}
