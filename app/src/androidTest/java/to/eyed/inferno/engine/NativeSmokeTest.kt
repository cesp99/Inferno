package to.eyed.inferno.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** WP1 acceptance (b): library loads, system info reports the expected ISA flags, topology matches the Seeker. */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {

    @Test
    fun systemInfoReportsDotprodAndFp16() {
        NativeTestSupport.ensureBackend()
        val info = LlamaNative.systemInfo()
        Log.i(NativeTestSupport.TAG, info)
        assertTrue(info, info.contains("DOTPROD = 1"))
        assertTrue(info, info.contains("FP16_VA = 1"))
        assertTrue(info, !info.contains("MATMUL_INT8 = 1"))   // b10991 prints only the flags that are present
    }

    @Test
    fun cpuTopologyMatchesBigLittle() {
        val t = LlamaNative.cpuTopology()
        Log.i(NativeTestSupport.TAG, "cpuTopology=${t.joinToString()}")
        assertEquals(Cpu.SIZE, t.size)
        assertTrue(t[Cpu.N_CORES] >= 4)
        assertTrue(t[Cpu.N_BIG] in 1..t[Cpu.N_CORES])
        assertEquals(t[Cpu.N_BIG], Integer.bitCount(t[Cpu.BIG_MASK]))
        assertEquals(1, t[Cpu.HAS_DOTPROD])
        assertEquals(1, t[Cpu.HAS_FP16])
        assertEquals(0, t[Cpu.HAS_SVE])
        if (android.os.Build.MODEL.contains("Seeker", ignoreCase = true)) {
            assertEquals(4, t[Cpu.N_BIG])
            assertEquals(0xF0, t[Cpu.BIG_MASK])
            assertEquals(0, t[Cpu.HAS_I8MM])
        }
    }

    @Test
    fun errorsNeverThrow() {
        NativeTestSupport.ensureBackend()
        // stale handles and missing files are reported through lastError, never as exceptions
        assertEquals(0, LlamaNative.contextNCtx(0x1234L))
        assertTrue(LlamaNative.lastErrorString().contains("stale"))
        val est = LlamaNative.estimateMemory("/nonexistent.gguf", null, NativeTestSupport.ctxParams(4096))
        assertEquals(0, est.size)
        assertTrue(LlamaNative.lastErrorString().isNotEmpty())
        assertEquals(null, LlamaNative.generateNext(0L))
        LlamaNative.cancel()          // any thread, no handle
        LlamaNative.estimateCacheClear()
    }
}
