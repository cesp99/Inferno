package to.eyed.inferno.engine

import kotlinx.serialization.Serializable

@Serializable
data class GenerationParams(
    val temperature: Float = 0.7f,      // <= 0 => greedy
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val typicalP: Float = 1.0f,
    val repeatPenalty: Float = 1.0f,
    val repeatLastN: Int = 64,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,
    val dryMultiplier: Float = 0f,      // <= 0 => DRY sampler not added; when added, sequence breakers are the llama.cpp common defaults {"\n", ":", "\"", "*"} (fixed in native, 4.5 step 3)
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyLastN: Int = -1,
    val seed: Int = -1,                 // -1 => LLAMA_DEFAULT_SEED
    val maxTokens: Int = 0,             // 0 => until EOS / context full
) {
    /** True when any of repeat/frequency/presence penalties is non-neutral (native adds the penalties sampler only then). */
    val hasPenalties: Boolean get() = repeatPenalty != 1.0f || frequencyPenalty != 0f || presencePenalty != 0f
    fun toNativeFloats() = FloatArray(SmpF.SIZE).also { it[SmpF.TEMP] = temperature; it[SmpF.TOP_P] = topP; it[SmpF.MIN_P] = minP
        it[SmpF.TYPICAL_P] = typicalP; it[SmpF.REPEAT_PENALTY] = repeatPenalty; it[SmpF.FREQ_PENALTY] = frequencyPenalty
        it[SmpF.PRESENCE_PENALTY] = presencePenalty; it[SmpF.DRY_MULTIPLIER] = dryMultiplier; it[SmpF.DRY_BASE] = dryBase }
    fun toNativeInts() = IntArray(SmpI.SIZE).also { it[SmpI.TOP_K] = topK; it[SmpI.REPEAT_LAST_N] = repeatLastN
        it[SmpI.DRY_ALLOWED_LENGTH] = dryAllowedLength; it[SmpI.DRY_PENALTY_LAST_N] = dryPenaltyLastN; it[SmpI.SEED] = seed }
}
