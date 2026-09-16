package to.eyed.inferno.models

/**
 * Curated catalog (spec 5.3 + 12.2 corrections, ranked per research/model-catalog-2026.md §1). Byte sizes and
 * sha256 are the Hugging Face LFS values (`/api/models/<repo>?blobs=true`, captured 2026-09-16); every URL was
 * HEAD-verified the same day. Speeds are bench-seeker.md measurements on the Seeker (4 pinned A78 threads):
 * pp128/tg32 and the 448 px image-encode time.
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co"
    private fun url(repo: String, file: String) = "$HF/$repo/resolve/main/$file"

    private const val APACHE = "Apache-2.0"
    private const val LFM = "LFM Open License v1.0"
    private const val MB = 1L shl 20

    /** Qwen3.5 / Qwen3 style: reasoning between <think> tags, toggled purely by the assistant prefix. */
    private val qwenThinking = ThinkingSpec(
        openTag = "<think>", closeTag = "</think>", defaultOn = false,
        disablePrefix = "<think>\n\n</think>\n\n", enablePrefix = "<think>\n",
    )
    private val qwenSampling = SamplingDefaults(temperature = 0.7f, topP = 0.8f, topK = 20, minP = 0f, presencePenalty = 1.5f)
    private val lfmSampling = SamplingDefaults(temperature = 0.1f, topP = 0.95f, topK = 50, minP = 0.15f, repeatPenalty = 1.05f)

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen3.5-2b-q4_0", displayName = "Qwen3.5 2B", family = ModelFamily.QWEN35, tier = ModelTier.BALANCED,
            paramsB = 2.0f, license = APACHE, contextMax = 262_144, defaultCtx = 32_768, kvBytesPerTokenF16 = 12_288,
            text = ModelFileSpec("Qwen3.5-2B-Q4_0.gguf", url("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_0.gguf"), 1_214_873_856,
                "cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-F16.gguf", url("unsloth/Qwen3.5-2B-GGUF", "mmproj-F16.gguf"), 668_227_264,
                "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7", Quant.F16),
            thinking = qwenThinking, sampling = qwenSampling,
            imageTokensMin = 0, imageTokensMax = 512, dynamicResolution = true, maxImageEdgePx = 1536, encoderPeakBytes = 700 * MB,
            estTgTps = "12-15",   // measured 2026-09-16: pp 86.6 / tg 14.8 (11.6 at sustained clocks)
            blurb = "Best all-round chat and vision for this phone; optional thinking mode.",
            recommended = true, encodeMsAt448 = 5100,
        ),
        CatalogModel(
            id = "minicpm-v-4.6-q4_0", displayName = "MiniCPM-V 4.6", family = ModelFamily.MINICPMV, tier = ModelTier.FASTEST,
            paramsB = 1.3f, license = APACHE, contextMax = 262_144, defaultCtx = 32_768, kvBytesPerTokenF16 = 12_288,
            text = ModelFileSpec("MiniCPM-V-4_6-Q4_0.gguf", url("openbmb/MiniCPM-V-4.6-gguf", "MiniCPM-V-4_6-Q4_0.gguf"), 501_256_896,
                "cdfb3ae7533d4702fc8bcaab0636e3dd1d36f700f09e674f4993e2750975481a", Quant.Q4_0),
            // ggml-org Q8_0 projector (12.2): 381 MB smaller than openbmb's f16 and the one WP1 (j) passed with at 448 px.
            mmproj = ModelFileSpec("mmproj-MiniCPM-V-4.6-Q8_0.gguf", url("ggml-org/MiniCPM-V-4.6-GGUF", "mmproj-MiniCPM-V-4.6-Q8_0.gguf"), 727_954_528,
                "3d8249cdd0e1cb699644eb021fbcc04320aad89fa5dc9234ef94db0846556581", Quant.Q8_0),
            // The Instruct checkpoint never emits <think>; thinking stays off (12.2) and the disable prefix is injected explicitly.
            thinking = ThinkingSpec(openTag = "<think>", closeTag = "</think>", defaultOn = false, disablePrefix = "<think>\n\n</think>\n\n", enablePrefix = null),
            sampling = SamplingDefaults(temperature = 0.7f, topP = 0.8f, topK = 100, minP = 0f, repeatPenalty = 1.05f),
            // llava-uhd slicing ignores image_max_tokens: 448 px = overview tile only. Never raise this cap (kernel-panic incident, 5.3).
            imageTokensMin = 0, imageTokensMax = 512, dynamicResolution = false, maxImageEdgePx = 448,
            encoderPeakBytes = 600 * MB,   // measured 2026-09-16: peak RSS 1.73 GB with 1.23 GB of weights at 448 px (WP1 acceptance j)
            estTgTps = "25-30",   // measured 2026-09-16: pp 199 / tg 29.7
            blurb = "Fastest vision model here; strong OCR for its size. Images are capped at 448 px.",
            encodeMsAt448 = 5200,
        ),
        CatalogModel(
            id = "lfm2.5-vl-1.6b-q4_0", displayName = "LFM2.5-VL 1.6B", family = ModelFamily.LFM2VL, tier = ModelTier.FASTEST,
            paramsB = 1.6f, license = LFM, contextMax = 32_768, defaultCtx = 32_768, kvBytesPerTokenF16 = 12_288,
            text = ModelFileSpec("LFM2.5-VL-1.6B-Q4_0.gguf", url("LiquidAI/LFM2.5-VL-1.6B-GGUF", "LFM2.5-VL-1.6B-Q4_0.gguf"), 695_752_480,
                "8186364a4e7c3ad30f6dd3d3b7a4e0074c77dd91eed6cad5d8be9090ce285804", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-LFM2.5-VL-1.6b-Q8_0.gguf", url("LiquidAI/LFM2.5-VL-1.6B-GGUF", "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf"), 583_109_888,
                "2ce89e610c56f3198ece2b86cf61743a08b9307279c89125eb2412ebb908689d", Quant.Q8_0),
            thinking = ThinkingSpec(), sampling = lfmSampling,
            imageTokensMin = 64, imageTokensMax = 256, dynamicResolution = true, maxImageEdgePx = 512, encoderPeakBytes = 250 * MB,
            estTgTps = "25-29",   // measured 2026-09-16: pp 150 / tg 29.1
            blurb = "Very fast replies with a cheap prefill; one 512 px tile per image.",
            encodeMsAt448 = 6200,
        ),
        CatalogModel(
            id = "gemma-4-e2b-q4_0", displayName = "Gemma 4 E2B", family = ModelFamily.GEMMA4, tier = ModelTier.QUALITY,
            paramsB = 5.1f, license = APACHE, contextMax = 131_072, defaultCtx = 16_384,
            // Only the 3 global-attention layers grow with context (1 KV head x 512 x 2 x 2 B); the 12 SWA layers stay ~12 MB with swa_full=false.
            kvBytesPerTokenF16 = 6_144,
            // Official Google QAT file (12.2): ~1.9 GB of it is the per-layer-embedding table that stays mmapped.
            text = ModelFileSpec("gemma-4-E2B_q4_0-it.gguf", url("google/gemma-4-E2B-it-qat-q4_0-gguf", "gemma-4-E2B_q4_0-it.gguf"), 3_349_516_256,
                "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-gemma-4-E2B-it-Q8_0.gguf", url("ggml-org/gemma-4-E2B-it-GGUF", "mmproj-gemma-4-E2B-it-Q8_0.gguf"), 557_368_064,
                "9406f99c16d68cda4f1f0552192dcc99021ea1fc6d2fd50b1dc3ccf30d04b292", Quant.Q8_0),
            // Thinking is opt-in via "<|think|>" in the system turn (WP1 formatter), not an assistant prefix; off in v1.
            thinking = ThinkingSpec(),
            sampling = SamplingDefaults(temperature = 1.0f, topP = 0.95f, topK = 64, minP = 0f),
            imageTokensMin = 70, imageTokensMax = 560, dynamicResolution = false, maxImageEdgePx = 896, encoderPeakBytes = 500 * MB,
            estTgTps = "8-11",   // measured 2026-09-16 at sustained clocks: pp 72.0 / tg 8.8
            blurb = "Highest answer quality; the cheapest image encoder of the list (2.6 s).",
            encodeMsAt448 = 2600,
        ),
        CatalogModel(
            id = "lfm2.5-vl-3b-q4_0", displayName = "LFM2.5-VL 3B", family = ModelFamily.LFM2VL, tier = ModelTier.QUALITY,
            paramsB = 3.1f, license = LFM, contextMax = 32_768, defaultCtx = 32_768,
            kvBytesPerTokenF16 = 20_480,   // hybrid conv + attention; not re-derived, budgeted below the research's 25 KB ceiling
            text = ModelFileSpec("LFM2.5-VL-3B-Q4_0.gguf", url("LiquidAI/LFM2.5-VL-3B-GGUF", "LFM2.5-VL-3B-Q4_0.gguf"), 1_593_894_944,
                "364224c6bd45b4ff4bf8af71f7be384ea166fa0f573d68bc0b6e10ef9ae7ae58", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-LFM2.5-VL-3B-Q8_0.gguf", url("LiquidAI/LFM2.5-VL-3B-GGUF", "mmproj-LFM2.5-VL-3B-Q8_0.gguf"), 583_109_984,
                "ecbbe7097f696dba67172738d79c9f01132cdb6c0b457606315e268df3d67e64", Quant.Q8_0),
            thinking = ThinkingSpec(), sampling = lfmSampling.copy(temperature = 0.2f),
            imageTokensMin = 64, imageTokensMax = 256, dynamicResolution = true, maxImageEdgePx = 512, encoderPeakBytes = 250 * MB,
            estTgTps = "9-12",   // measured 2026-09-16 at sustained clocks: pp 62.6 / tg 9.7
            blurb = "Newest general vision model; best at screenshots and grounding.",
            encodeMsAt448 = 6200,
        ),
        CatalogModel(
            id = "qwen3.5-0.8b-q8_0", displayName = "Qwen3.5 0.8B", family = ModelFamily.QWEN35, tier = ModelTier.FASTEST,
            paramsB = 0.8f, license = APACHE, contextMax = 262_144, defaultCtx = 32_768, kvBytesPerTokenF16 = 12_288,
            // Q8_0 on purpose: the 248k-vocab embeddings dominate this file and Q4 hurts it.
            text = ModelFileSpec("Qwen3.5-0.8B-Q8_0.gguf", url("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q8_0.gguf"), 811_843_840,
                "0ad885ffd4bb022fc4f0d33a3308fa108ef8613159d3b3a67e23abca056b7a6c", Quant.Q8_0),
            mmproj = ModelFileSpec("mmproj-F16.gguf", url("unsloth/Qwen3.5-0.8B-GGUF", "mmproj-F16.gguf"), 204_987_232,
                "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453", Quant.F16),
            thinking = qwenThinking, sampling = qwenSampling,
            imageTokensMin = 0, imageTokensMax = 512, dynamicResolution = true, maxImageEdgePx = 1536, encoderPeakBytes = 700 * MB,
            estTgTps = "20-22",   // measured 2026-09-16: pp 244 / tg 21.5
            blurb = "Small and quick; fine for short questions and simple image descriptions.",
        ),
        CatalogModel(
            id = "qwen3-vl-2b-q4_0", displayName = "Qwen3-VL 2B", family = ModelFamily.QWEN3VL, tier = ModelTier.BALANCED,
            paramsB = 2.1f, license = APACHE, contextMax = 262_144, defaultCtx = 16_384, kvBytesPerTokenF16 = 114_688,
            text = ModelFileSpec("Qwen3-VL-2B-Instruct-Q4_0.gguf", url("unsloth/Qwen3-VL-2B-Instruct-GGUF", "Qwen3-VL-2B-Instruct-Q4_0.gguf"), 1_056_784_064,
                "d9ca31f524d063c04e49d1af7b0b37061b21e7f8a7e460141654efe287600234", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf", url("Qwen/Qwen3-VL-2B-Instruct-GGUF", "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"), 445_053_216,
                "f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82", Quant.Q8_0),
            thinking = ThinkingSpec(), sampling = SamplingDefaults(temperature = 0.7f, topP = 0.8f, topK = 20, minP = 0f),
            imageTokensMin = 0, imageTokensMax = 512, dynamicResolution = true, maxImageEdgePx = 1536, encoderPeakBytes = 700 * MB,
            estTgTps = "16-18",   // measured 2026-09-16: pp 97.6 / tg 18.4
            blurb = "Classic transformer, the safest fallback; large KV cache so context is capped.",
        ),
        CatalogModel(
            id = "qwen3.5-4b-q4_0", displayName = "Qwen3.5 4B", family = ModelFamily.QWEN35, tier = ModelTier.QUALITY,
            paramsB = 4.3f, license = APACHE, contextMax = 262_144, defaultCtx = 16_384, kvBytesPerTokenF16 = 32_768,
            text = ModelFileSpec("Qwen3.5-4B-Q4_0.gguf", url("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_0.gguf"), 2_583_221_408,
                "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb", Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj-F16.gguf", url("unsloth/Qwen3.5-4B-GGUF", "mmproj-F16.gguf"), 672_423_616,
                "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864", Quant.F16),
            thinking = qwenThinking, sampling = qwenSampling,
            imageTokensMin = 0, imageTokensMax = 512, dynamicResolution = true, maxImageEdgePx = 1024, encoderPeakBytes = 500 * MB,
            estTgTps = "6-7",
            blurb = "Strongest reasoning and OCR of the general models; needs most of the phone's memory.",
        ),
        CatalogModel(
            id = "lfm2.5-vl-450m-q8_0", displayName = "LFM2.5-VL 450M", family = ModelFamily.LFM2VL, tier = ModelTier.FASTEST,
            paramsB = 0.45f, license = LFM, contextMax = 32_768, defaultCtx = 32_768, kvBytesPerTokenF16 = 12_288,
            text = ModelFileSpec("LFM2.5-VL-450M-Q8_0.gguf", url("LiquidAI/LFM2.5-VL-450M-GGUF", "LFM2.5-VL-450M-Q8_0.gguf"), 379_219_104,
                "263aca93039e22140d55e046831c700c796affa8143d7638581c488a30c712bc", Quant.Q8_0),
            mmproj = ModelFileSpec("mmproj-LFM2.5-VL-450m-Q8_0.gguf", url("LiquidAI/LFM2.5-VL-450M-GGUF", "mmproj-LFM2.5-VL-450m-Q8_0.gguf"), 102_815_168,
                "ebfc428baa37efad8bae93864f914b2634a09009f91ad59f974fe1a1565d8561", Quant.Q8_0),
            thinking = ThinkingSpec(), sampling = lfmSampling,
            imageTokensMin = 64, imageTokensMax = 256, dynamicResolution = true, maxImageEdgePx = 512, encoderPeakBytes = 200 * MB,
            estTgTps = "35-45",
            blurb = "Ultra-light: captions and simple questions about a picture in under half a gigabyte.",
        ),
    )

    /** Rows gated out of every list (5.3). Empty since MiniCPM-V 4.6 passed WP1 acceptance (j) with the Q8_0 projector at 448 px. */
    private val hidden: Set<String> = emptySet()

    val visible: List<CatalogModel> = models.filter { it.tier != ModelTier.LEGACY && it.id !in hidden }
    private val byId: Map<String, CatalogModel> = models.associateBy { it.id }
    fun byId(id: String): CatalogModel? = byId[id]
    val recommended: CatalogModel = models.first { it.recommended }
}
