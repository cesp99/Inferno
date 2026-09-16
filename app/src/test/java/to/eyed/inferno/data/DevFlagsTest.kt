package to.eyed.inferno.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.engine.InferenceEngine
import to.eyed.inferno.ui.chat.metaLine

class DevFlagsTest {
    @Test fun normalUserSeesNothingEvenThoughSubTogglesDefaultOn() {
        val s = SettingsState()
        assertFalse(s.developerMode)
        assertTrue(DevFlag.entries.all { it.get(s) })
        assertFalse(s.devGenerationStats); assertFalse(s.devContextMeter); assertFalse(s.devTurnDetails)
        assertFalse(s.devBenchmark); assertFalse(s.devModelTechSpecs); assertFalse(s.devThermalInfo); assertFalse(s.devTokenCounter)
    }

    @Test fun masterSwitchAndSubToggleCombine() {
        val on = SettingsState(experienceLevel = ExperienceLevel.DEVELOPER)
        assertTrue(on.developerMode)
        assertTrue(on.devBenchmark)
        assertFalse(DevFlag.BENCHMARK.set(on, false).devBenchmark)
        assertTrue(DevFlag.BENCHMARK.set(on, false).devContextMeter)
    }

    @Test fun powerUserGetsTheRingButNoneOfTheDeveloperNumbers() {
        val power = SettingsState(experienceLevel = ExperienceLevel.POWER)
        assertTrue(power.powerUser)
        assertFalse(power.developerMode)
        assertTrue(power.showsContextRing)
        assertFalse(power.devContextMeter); assertFalse(power.devGenerationStats); assertFalse(power.devThermalInfo)
        assertFalse(power.devModelTechSpecs); assertFalse(power.devBenchmark); assertFalse(power.devTokenCounter); assertFalse(power.devTurnDetails)
        val normal = SettingsState()
        assertFalse(normal.powerUser)
        assertFalse(normal.showsContextRing)
    }

    @Test fun developerCanStillSwitchTheRingOff() {
        val dev = SettingsState(experienceLevel = ExperienceLevel.DEVELOPER)
        assertTrue(dev.powerUser)
        assertTrue(dev.showsContextRing)
        assertFalse(DevFlag.CONTEXT_METER.set(dev, false).showsContextRing)
    }

    @Test fun metaLineHidesNumbersForNormalUsers() {
        val clean = MessageStats(promptTokens = 40, generatedTokens = 120, prefillMs = 200, decodeMs = 8_000, kvUsedTokens = 160, nCtx = 8192, finishReason = "EOS", modelId = "m")
        assertNull(metaLine(clean, tech = false))
        assertEquals("stopped", metaLine(clean.copy(finishReason = "CANCELLED"), tech = false))
        val dev = metaLine(clean, tech = true)!!
        assertTrue(dev, dev.startsWith("15.0 tok/s · 120 tokens · 8.2 s · prefill 200 ms · ctx 160/8,192"))
    }

    @Test fun bufferTypeLinesCollapseToOneSummary() {
        val log = "load_tensors:  CPU_KLEIDIAI model buffer size =  1103.12 MiB\nload_tensors:   CPU_Mapped model buffer size =   300.50 MiB\nother line\n"
        assertEquals("CPU_KLEIDIAI 1.1 GB · CPU_Mapped 300 MB", InferenceEngine.parseBufferTypes(log))
        assertEquals("", InferenceEngine.parseBufferTypes(""))
    }
}
