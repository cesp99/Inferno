package to.eyed.inferno.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamsNativeArrayTest {
    @Test fun defaultsMapToTheSmpLayout() {
        val p = GenerationParams()
        val f = p.toNativeFloats(); val i = p.toNativeInts()
        assertEquals(SmpF.SIZE, f.size); assertEquals(SmpI.SIZE, i.size)
        assertEquals(0.7f, f[SmpF.TEMP]); assertEquals(0.95f, f[SmpF.TOP_P]); assertEquals(0.05f, f[SmpF.MIN_P])
        assertEquals(1.0f, f[SmpF.TYPICAL_P]); assertEquals(1.0f, f[SmpF.REPEAT_PENALTY]); assertEquals(0f, f[SmpF.FREQ_PENALTY])
        assertEquals(0f, f[SmpF.PRESENCE_PENALTY]); assertEquals(0f, f[SmpF.DRY_MULTIPLIER]); assertEquals(1.75f, f[SmpF.DRY_BASE])
        assertEquals(40, i[SmpI.TOP_K]); assertEquals(64, i[SmpI.REPEAT_LAST_N]); assertEquals(2, i[SmpI.DRY_ALLOWED_LENGTH])
        assertEquals(-1, i[SmpI.DRY_PENALTY_LAST_N]); assertEquals(-1, i[SmpI.SEED])
        assertFalse(p.hasPenalties)
    }

    @Test fun everyFieldLandsInItsSlot() {
        val p = GenerationParams(temperature = 0.1f, topK = 7, topP = 0.5f, minP = 0.2f, typicalP = 0.9f, repeatPenalty = 1.1f,
            repeatLastN = 128, frequencyPenalty = 0.3f, presencePenalty = 1.5f, dryMultiplier = 0.8f, dryBase = 2f,
            dryAllowedLength = 3, dryPenaltyLastN = 256, seed = 42, maxTokens = 100)
        assertArrayEquals(floatArrayOf(0.1f, 0.5f, 0.2f, 0.9f, 1.1f, 0.3f, 1.5f, 0.8f, 2f), p.toNativeFloats(), 0f)
        assertArrayEquals(intArrayOf(7, 128, 3, 256, 42), p.toNativeInts())
        assertTrue(p.hasPenalties)
    }

    @Test fun penaltiesFlagFollowsAnyNonNeutralPenalty() {
        assertTrue(GenerationParams(repeatPenalty = 1.05f).hasPenalties)
        assertTrue(GenerationParams(frequencyPenalty = 0.1f).hasPenalties)
        assertTrue(GenerationParams(presencePenalty = 1.5f).hasPenalties)
        assertFalse(GenerationParams(dryMultiplier = 0.5f).hasPenalties)
    }

    @Test fun catalogQwenDefaultsRoundTrip() {
        // Qwen3.5 catalog sampling (0.7 / 0.8 / 20, presence 1.5) applied over the defaults.
        val p = GenerationParams(temperature = 0.7f, topP = 0.8f, topK = 20, minP = 0f, presencePenalty = 1.5f)
        assertEquals(20, p.toNativeInts()[SmpI.TOP_K]); assertEquals(1.5f, p.toNativeFloats()[SmpF.PRESENCE_PENALTY])
    }
}
