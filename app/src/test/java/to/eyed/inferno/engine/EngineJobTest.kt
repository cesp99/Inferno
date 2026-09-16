package to.eyed.inferno.engine

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.imagegen.JobGate

class EngineJobTest {
    @Test fun jobsAreSerialisedAcrossCoroutines() = runBlocking {
        val running = AtomicInteger(); val maxRunning = AtomicInteger()
        val jobs = (1..8).map { n ->
            async(Dispatchers.Default) {
                EngineJob.withJob("job$n") {
                    val now = running.incrementAndGet(); maxRunning.updateAndGet { maxOf(it, now) }
                    delay(10); running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals(1, maxRunning.get())
        assertNull(EngineJob.owner.value); assertFalse(EngineJob.isBusy)
    }

    @Test fun reentrantFromTheSameCoroutineChain() = runBlocking {
        // ImageGenRepository: jobGate.withJob { coordinator.releaseForImageGen() } -> InferenceEngine.unload() -> withJob again.
        val gate: JobGate = EngineJob
        withTimeout(2_000) {
            gate.withJob("image") {
                assertEquals("image", EngineJob.owner.value)
                assertTrue(EngineJob.heldByCaller())
                val inner = EngineJob.withJob("llm-unload") { withContext(Dispatchers.Default) { "nested" } }
                assertEquals("nested", inner)
                assertEquals("image", EngineJob.owner.value)      // the outer holder keeps the job
            }
        }
        assertNull(EngineJob.owner.value); assertFalse(EngineJob.heldByCaller())
    }

    @Test fun unrelatedCoroutineWaitsForTheHolder() = runBlocking {
        val order = mutableListOf<String>()
        val outer = launch(Dispatchers.Default) {
            EngineJob.withJob("a") { order.add("a-start"); delay(100); order.add("a-end") }
        }
        delay(20)
        val other = launch(Dispatchers.Default) { EngineJob.withJob("b") { order.add("b") } }
        outer.join(); other.join()
        assertEquals(listOf("a-start", "a-end", "b"), order)
    }

    @Test fun tryAcquireIsRefusedWhileHeldAndReleasedAfterwards() = runBlocking {
        val held = launch(Dispatchers.Default) { EngineJob.withJob("hold") { delay(150) } }
        delay(30)
        assertFalse(EngineJob.tryAcquire("trim"))
        held.join()
        assertTrue(EngineJob.tryAcquire("trim")); assertEquals("trim", EngineJob.owner.value)
        EngineJob.release("trim"); assertNull(EngineJob.owner.value); assertFalse(EngineJob.isBusy)
    }

    @Test fun exceptionsReleaseTheJob() = runBlocking {
        runCatching { EngineJob.withJob("boom") { error("x") } }
        assertFalse(EngineJob.isBusy); assertNull(EngineJob.owner.value)
    }
}
