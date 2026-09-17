package to.eyed.inferno.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured from the Solana Seeker (4x Cortex-A55 0xd05 + 4x Cortex-A78 0xd41); must agree with native cpuTopology(): 8 cores, 4 big, mask 0xF0. */
class CpuTopologyProbeTest {
    private val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val seekerCpuinfo: String = buildString {
        for (i in 0 until 8) {
            val big = i >= 4
            append("processor\t: $i\nBogoMIPS\t: 26.00\nFeatures\t: $features\nCPU implementer\t: 0x41\nCPU architecture: 8\n")
            append("CPU variant\t: ${if (big) "0x1" else "0x2"}\nCPU part\t: ${if (big) "0xd41" else "0xd05"}\nCPU revision\t: 0\n\n")
        }
    }
    private val seekerCapacity = listOf<Long?>(228, 228, 228, 228, 1024, 1024, 1024, 1024)
    private val seekerMaxFreq = listOf<Long?>(2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_500_000, 2_500_000, 2_500_000, 2_500_000)

    @Test fun seekerProbeMatchesNativeTopology() {
        val p = CpuTopology.parse(seekerCpuinfo, CpuTopology.parseCoreCount(seekerCpuinfo, "0-7\n"), seekerCapacity, seekerMaxFreq)
        assertEquals(8, p.nCores); assertEquals(4, p.nBig); assertEquals(0xF0, p.bigMask)
        assertTrue(p.hasDotprod); assertTrue(p.hasFp16); assertFalse(p.hasI8mm); assertFalse(p.hasSve)
    }

    @Test fun coreCountFallsBackToProcessorLines() {
        assertEquals(8, CpuTopology.parseCoreCount(seekerCpuinfo, null))
        assertEquals(6, CpuTopology.parseCoreCount(seekerCpuinfo, "0-5"))
    }

    @Test fun maxFreqIsUsedWhenCapacityIsUnreadable() {
        val p = CpuTopology.parse(seekerCpuinfo, 8, List(8) { null }, seekerMaxFreq)
        assertEquals(0xF0, p.bigMask); assertEquals(4, p.nBig)
    }

    @Test fun homogeneousOrUnknownLayoutMakesEveryCoreBig() {
        val p = CpuTopology.parse(seekerCpuinfo, 8, List(8) { null }, List(8) { null })
        assertEquals(0xFF, p.bigMask); assertEquals(8, p.nBig)
        val q = CpuTopology.parse(seekerCpuinfo, 8, List(8) { 1024L }, seekerMaxFreq)
        assertEquals(0xFF, q.bigMask)
    }

    // Galaxy Z Fold (Snapdragon 8 Elite Gen 5): 2 prime cores at 1024 + 6 performance cores at 741, no efficiency cluster.
    private val eliteCapacity = listOf<Long?>(741, 741, 741, 741, 741, 741, 1024, 1024)
    private val eliteMaxFreq = listOf<Long?>(3_628_800, 3_628_800, 3_628_800, 3_628_800, 3_628_800, 3_628_800, 4_742_400, 4_742_400)

    @Test fun secondPerformanceTierCountsAsBig() {
        // Only the max tier would pin 4 threads on 2 cores; every core of this SoC is fast enough to count.
        val p = CpuTopology.parse(seekerCpuinfo, 8, eliteCapacity, eliteMaxFreq)
        assertEquals(0xFF, p.bigMask); assertEquals(8, p.nBig)
        // 1 prime + 3 big + 4 little: prime and big are pinnable, little is not.
        val q = CpuTopology.parse(seekerCpuinfo, 8, listOf<Long?>(300, 300, 300, 300, 810, 810, 810, 1024), List(8) { null })
        assertEquals(0xF0, q.bigMask); assertEquals(4, q.nBig)
    }

    @Test fun frequencyFallbackIsStricter() {
        // 3.6 / 4.7 GHz = 0.77: below the 0.85 frequency line, so only the prime pair; the thread clamp covers the rest.
        val p = CpuTopology.parse(seekerCpuinfo, 8, List(8) { null }, eliteMaxFreq)
        assertEquals(0xC0, p.bigMask); assertEquals(2, p.nBig)
    }

    @Test fun pinnedThreadsNeverExceedBigCores() {
        val two = CpuTopology(CpuTopology.parse(seekerCpuinfo, 8, List(8) { null }, eliteMaxFreq), "test", 8L shl 30) { 4L shl 30 }
        assertEquals(2, two.threadsFor(4, pinned = true)); assertEquals(4, two.threadsFor(4, pinned = false)); assertEquals(1, two.threadsFor(1, pinned = true))
        val four = EngineTestFixtures.cpu({ 4L shl 30 })
        assertEquals(4, four.threadsFor(4, pinned = true)); assertEquals(3, four.threadsFor(3, pinned = true)); assertEquals(8, four.threadsFor(8, pinned = false))
    }

    @Test fun featuresAreIntersectedAcrossClusters() {
        // A little core without dotprod must disable the gate for the whole SoC (pinning is optional).
        val mixed = seekerCpuinfo.replaceFirst("dcpop asimddp", "dcpop")
        val p = CpuTopology.parse(mixed, 8, seekerCapacity, seekerMaxFreq)
        assertFalse(p.hasDotprod); assertTrue(p.hasFp16)
        val withI8mm = seekerCpuinfo.replace("asimddp", "asimddp i8mm sve")
        val q = CpuTopology.parse(withI8mm, 8, seekerCapacity, seekerMaxFreq)
        assertTrue(q.hasI8mm); assertTrue(q.hasSve)
    }

    @Test fun emptyCpuinfoYieldsNoFeatures() {
        val p = CpuTopology.parse("", 4, List(4) { null }, List(4) { null })
        assertFalse(p.hasDotprod); assertFalse(p.hasFp16); assertEquals(0xF, p.bigMask)
    }

    @Test fun topologyWrapperExposesProbeAndRam() {
        var avail = 4L shl 30
        val cpu = EngineTestFixtures.cpu({ avail })
        assertEquals(4, cpu.nBig); assertEquals(0xF0, cpu.bigMask); assertTrue(cpu.isSupported)
        assertEquals(4L shl 30, cpu.availRamBytes()); avail = 1L shl 30; assertEquals(1L shl 30, cpu.availRamBytes())
    }
}
