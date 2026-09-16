package to.eyed.inferno.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import to.eyed.inferno.engine.EngineTestFixtures.GB
import to.eyed.inferno.engine.EngineTestFixtures.MB
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import to.eyed.inferno.models.ThinkingSpec

/**
 * generate() state machine over the scripted [FakeNative]: exactly one terminal event on every path (EOS, collector
 * cancel, NeedsTruncation, native error, -5 memory, watchdog), assistant-prefix / parser seeding, thermal thread
 * stepping, the Suspended -> Resuming -> Ready round trip and the load-time budget gate.
 */
class InferenceEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val native = FakeNative()
    private var avail = 4L * GB
    private var threads = 4
    private var tooHot = false
    private val thermal = object : ThermalGovernor(null, scope, workerTids = { IntArray(0) }) {
        override fun threadsFor(requested: Int): Int = minOf(requested, threads)
        override fun pauseImageEncoding(): Boolean = tooHot
    }
    private val engine = InferenceEngine(EngineTestFixtures.cpu({ avail }), thermal, scope, null, native)
    private val model = EngineTestFixtures.local()
    private val config = ContextConfig(nCtx = 8192, nBatch = 1024, nUbatch = 1024)

    @After fun tearDown() { runBlocking { withTimeout(5_000) { engine.unload() } }; scope.cancel() }

    private fun load(m: LocalModel = model, visionAllowed: Boolean = true): LoadedModel =
        runBlocking { engine.load(m, config, ImageDetail.BALANCED, useMmap = true, visionAllowed = visionAllowed) }

    private fun gen(images: List<PromptImage> = emptyList(), thinkingEnabled: Boolean = false, spec: ThinkingSpec = EngineTestFixtures.qwenThinking,
                    params: GenerationParams = GenerationParams()) =
        engine.generate("conv-1", listOf(PromptMessage("user", "Hi", images.map { it.id })), images, params, spec, thinkingEnabled, ImageDetail.BALANCED)

    private fun List<GenerationEvent>.terminals() = filter { it.isTerminal }

    @Test fun loadProducesReadyWithModelFactsAndTheBudgetGateIsChecked() {
        val l = load()
        assertEquals(8192, l.context.nCtx); assertEquals("qwen35", l.arch); assertTrue(l.hasVision); assertFalse(l.visionResident)
        assertEquals("Qwen3.5 2B 🦉", l.description)
        assertTrue(engine.state.value is EngineState.Ready)
        assertNotNull(l.estimate); assertEquals(500 * MB, l.estimate!!.encoderPeakBytes)
        assertTrue(native.calls.first().startsWith("backendInit(240)"))                              // 0xF0 big mask
        assertTrue(native.calls.any { it.startsWith("estimate(8192)") })
        assertTrue(native.calls.any { it == "modelLoad(/models/qwen3.5-2b-q4_0/text.gguf,mmap=true,maxTok=512)" })
    }

    @Test fun loadIsRefusedWhenTheEstimateExceedsTheGate() {
        avail = 2L * GB          // budget 1.5 GB, gate 1.3 GB < resident 1.4 GB + kv + compute
        try { load(); fail("expected EngineException") } catch (e: EngineException) { assertTrue(e.message!!.startsWith("Not enough memory")) }
        val s = engine.state.value
        assertTrue(s is EngineState.Error && s.model == model)
        assertFalse(native.calls.any { it.startsWith("modelLoad") })
    }

    @Test fun eosPathEmitsPrefillTokensAndExactlyOneDone() = runBlocking {
        load()
        val events = gen().toList()
        assertEquals(listOf(GenerationEvent.Prefill(20, 40, false), GenerationEvent.Prefill(40, 40, false)), events.filterIsInstance<GenerationEvent.Prefill>())
        assertEquals("Hello world", events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text })
        val terminals = events.terminals()
        assertEquals(1, terminals.size)
        val done = terminals.single() as GenerationEvent.Done
        assertEquals(FinishReason.EOS, done.reason); assertEquals(40, done.stats.promptTokens); assertEquals(8192, done.stats.nCtx)
        assertEquals(42, engine.kvUsed.value)
        assertTrue(engine.state.value is EngineState.Ready)
        assertFalse(EngineJob.isBusy)
        // Thinking off => the disable prefix is injected; no image => availMem passed untouched.
        assertEquals("<think>\n\n</think>\n\n", String(native.lastPrefix!!))
        assertEquals(4L * GB, native.lastAvailMem)
    }

    @Test fun needsTruncationIsTheOnlyEvent() = runBlocking {
        load(); native.startRc = 2
        val events = gen().toList()
        assertEquals(listOf<GenerationEvent>(GenerationEvent.NeedsTruncation), events.terminals())
        assertTrue(engine.state.value is EngineState.Ready)
    }

    @Test fun nativeErrorBecomesOneErrorEvent() = runBlocking {
        load(); native.startRc = -3; native.errorText = "decode failed"
        val events = gen().toList()
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("decode failed")), events.terminals())
        assertTrue(engine.state.value is EngineState.Ready)
    }

    @Test fun decodeErrorAfterTokensStillYieldsOneTerminal() = runBlocking {
        load(); native.finish = NFinish.ERROR
        val events = gen().toList()
        assertEquals(2, events.filterIsInstance<GenerationEvent.Token>().size)
        assertEquals(1, events.terminals().size); assertTrue(events.last() is GenerationEvent.Error)
    }

    @Test fun imageTurnLoadsTheProjectorAndMapsMinusFive() = runBlocking {
        load()
        val img = PromptImage("img1", 448, 336, ByteArray(448 * 336 * 3))
        native.startRc = -5
        val events = gen(images = listOf(img)).toList()
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("Not enough memory to read this image")), events.terminals())
        assertTrue(events.contains(GenerationEvent.Resuming(LoadPhase.VISION)))
        assertTrue(native.calls.any { it == "mmprojLoad(threads=4,maxTok=512)" })
        assertEquals(4L * GB - 500 * MB, native.lastAvailMem)        // encoderPeakBytes subtracted for the native pre-encode check
        // A following text-only turn releases the projector (memory rule, spec 8).
        native.startRc = 0
        gen().toList()
        assertTrue(native.calls.contains("mmprojFree"))
        assertFalse((engine.state.value as EngineState.Ready).loaded.visionResident)
    }

    @Test fun oversizedImageIsRejectedBeforeNative() = runBlocking {
        load()
        val img = PromptImage("big", 2048, 1024, ByteArray(2048 * 1024 * 3))
        val events = gen(images = listOf(img)).toList()
        assertEquals(1, events.terminals().size)
        assertTrue((events.single() as GenerationEvent.Error).message.contains("edge cap"))
        assertFalse(native.calls.any { it.startsWith("generateStart") })
    }

    @Test fun visionDisabledSessionRefusesImages() = runBlocking {
        load(visionAllowed = false)
        val img = PromptImage("img1", 448, 336, ByteArray(448 * 336 * 3))
        val events = gen(images = listOf(img)).toList()
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("Not enough memory for images with this model")), events)
    }

    @Test fun collectorCancellationStopsNativeAndJoinsTheJob() = runBlocking {
        load(); native.blockUntilCancel = true
        val seen = mutableListOf<GenerationEvent>()
        val job = launch { gen().collect { seen += it; if (it is GenerationEvent.Token && it.text == " world") cancel() } }
        withTimeout(5_000) { job.join() }
        assertEquals(1, native.cancelCount.get())
        assertFalse(EngineJob.isBusy)                                  // cancel joined the native loop before returning
        assertTrue(engine.state.value is EngineState.Ready)
        assertTrue(seen.terminals().isEmpty())                         // a cancelled collector sees no terminal event
        // The engine is immediately usable again.
        native.blockUntilCancel = false
        assertEquals(1, gen().toList().terminals().size)
    }

    @Test fun memoryWatchdogCancelsUnloadsAndReportsNotEnoughMemory() = runBlocking {
        load(); native.blockUntilCancel = true
        val events = mutableListOf<GenerationEvent>()
        val job = launch { gen().collect { events += it; if (it is GenerationEvent.Token && it.text == " world") avail = 300 * MB } }
        withTimeout(5_000) { job.join() }
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("Not enough memory")), events.terminals())
        val s = engine.state.value
        assertTrue("state was $s", s is EngineState.Error && s.message == "Not enough memory")
        assertTrue(native.calls.contains("modelFree"))
        assertNull(engine.loaded)
    }

    @Test fun thinkingOnSeedsTheParserAndSplitsReasoning() = runBlocking {
        load(); native.pieces = listOf("plan", " it</think>", "\n\nanswer")
        val events = gen(thinkingEnabled = true).toList()
        assertEquals("<think>\n", String(native.lastPrefix!!))
        assertEquals("plan it", events.filterIsInstance<GenerationEvent.Thinking>().joinToString("") { it.text })
        assertEquals("answer", events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text })
        assertEquals(1, events.terminals().size)
    }

    @Test fun gemma4ThinkingUsesTheSystemFlagInsteadOfAPrefix() = runBlocking {
        native.templateName = "gemma4"
        load()
        gen(thinkingEnabled = true, spec = ThinkingSpec(openTag = "<|channel>", closeTag = "<channel|>")).toList()
        assertNull(native.lastPrefix)
        assertEquals("system", native.lastMessages.first().role)
        assertEquals("<|think|>", String(native.lastMessages.first().content))
        assertEquals(2, native.lastMessages.size)
    }

    @Test fun thermalStepDownAppliesAtTurnStartAndRestoresOnRecovery() = runBlocking {
        load()
        threads = 3
        gen().toList()
        assertTrue(native.calls.contains("setThreads(3,3,true,50)"))
        threads = 4
        gen().toList()
        assertTrue(native.calls.contains("setThreads(4,4,true,50)"))
    }

    @Test fun trimToSuspendedThenGenerateResumesWithoutUserAction() = runBlocking {
        load()
        engine.releaseForMemory(critical = true)
        withTimeout(2_000) { engine.state.first { it is EngineState.Suspended } }
        assertNull(engine.loaded); assertNotNull(engine.state.value.modelOrNull)
        val events = gen().toList()
        assertEquals(GenerationEvent.Resuming(LoadPhase.CONTEXT), events.first())
        assertEquals(2, native.contextCreated)
        assertEquals(1, events.terminals().size)
        assertTrue(engine.state.value is EngineState.Ready)
    }

    @Test fun samplingChangeIsPushedOncePerTurn() = runBlocking {
        load()
        val before = native.calls.count { it == "samplerSet" }
        gen(params = GenerationParams(temperature = 0.2f)).toList()
        gen(params = GenerationParams(temperature = 0.2f)).toList()
        assertEquals(before + 1, native.calls.count { it == "samplerSet" })
    }

    @Test fun benchIsCancellableAndRestoresReady() = runBlocking {
        load(); native.blockUntilCancel = true
        val job = launch { runCatching { engine.bench(256, 64, 1) { _, _ -> } } }
        delay(100); job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals(1, native.cancelCount.get())
        assertTrue(engine.state.value is EngineState.Ready)
        native.blockUntilCancel = false
        val r = engine.bench(256, 64, 1) { _, _ -> }
        assertEquals(20.0, r.tgTps, 0.0)
    }

    @Test fun generateWithoutModelEmitsOneError() = runBlocking {
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("No model loaded")), gen().toList())
    }

    @Test fun unloadReleasesEverythingAndGoesIdle() = runBlocking {
        load(); engine.unload()
        assertTrue(engine.state.value is EngineState.Idle)
        assertTrue(native.calls.containsAll(listOf("contextFree", "modelFree", "estimateCacheClear")))
        delay(50)
        assertEquals(0, native.cancelCount.get())                     // no gratuitous cancel when nothing was running
    }

    @Test fun countPromptTokensLeavesLoadingWhenTheProjectorFailsAfterAResume() = runBlocking {
        load()
        engine.releaseForMemory(critical = true)
        withTimeout(2_000) { engine.state.first { it is EngineState.Suspended } }
        native.mmprojLoadResult = false; native.errorText = "mtmd_init failed: projector"
        val img = PromptImage("img1", 448, 336, null)
        try {
            engine.countPromptTokens(listOf(PromptMessage("user", "Hi", listOf("img1"))), listOf(img)); fail("expected EngineException")
        } catch (e: EngineException) { assertTrue(e.message!!.contains("projector")) }
        // The context was recreated by the resume: the engine is Ready (not stranded in Loading) and usable.
        assertTrue("state=${engine.state.value}", engine.state.value is EngineState.Ready)
        assertFalse((engine.state.value as EngineState.Ready).loaded.visionResident)
        native.mmprojLoadResult = true
        assertEquals(1, gen().toList().terminals().size)
    }

    @Test fun severeThermalRefusesOnlyTheImageEncodeItself() = runBlocking {
        load()
        tooHot = true
        // Text-only turns and token counting keep working on a hot phone (the projector load is not an encode).
        assertTrue(gen().toList().terminals().single() is GenerationEvent.Done)
        val img = PromptImage("img1", 448, 336, ByteArray(448 * 336 * 3))
        engine.countPromptTokens(listOf(PromptMessage("user", "Hi", listOf("img1"))), listOf(img.copy(rgb = null)))
        assertTrue(native.calls.any { it.startsWith("mmprojLoad") })
        // A turn that has to encode a new image is refused at the encode callback and ends with one Error.
        val events = gen(images = listOf(img)).toList()
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Error("Phone is too hot to read images right now")), events.terminals())
        assertTrue(engine.state.value is EngineState.Ready)
        tooHot = false
        assertTrue(gen(images = listOf(img)).toList().terminals().single() is GenerationEvent.Done)
    }

    @Test fun backgroundTrimNeverCancelsARunningTurnAndUnloadsOnceIdle() = runBlocking {
        load(); native.blockUntilCancel = true
        val job = launch { gen().toList() }
        withTimeout(2_000) { engine.state.first { it is EngineState.Generating } }
        engine.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, keepModelLoaded = false)
        delay(200)
        assertEquals(0, native.cancelCount.get())
        assertTrue(engine.state.value is EngineState.Generating)
        job.cancel(); withTimeout(5_000) { job.join() }
        withTimeout(2_000) { engine.state.first { it is EngineState.Ready } }
        // Idle now: the same trim unloads.
        engine.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, keepModelLoaded = false)
        withTimeout(2_000) { engine.state.first { it is EngineState.Idle } }
        assertTrue(native.calls.contains("modelFree"))
    }

    @Test fun reconfigureFailurePublishesTheConfigTheEngineWillActuallyResume() = runBlocking {
        load()
        native.contextCreateResult = 0L
        try { engine.reconfigure(config.copy(nCtx = 32768)); fail("expected EngineException") } catch (e: EngineException) { }
        val s = engine.state.value as EngineState.Suspended
        assertEquals(8192, s.loaded.context.nCtx)                      // not the refused 32k
        assertEquals(0, engine.kvUsed.value)
        native.contextCreateResult = 2L
        val events = gen().toList()
        assertEquals(GenerationEvent.Resuming(LoadPhase.CONTEXT), events.first())
        assertTrue(native.calls.last { it.startsWith("contextCreate") } == "contextCreate(nCtx=8192,threads=4)")
        assertEquals(8192, (engine.state.value as EngineState.Ready).loaded.context.nCtx)
    }

    @Test fun projectorIsBuiltOnceWhenCountAndGenerateAgreeOnTheEffectiveTokens() = runBlocking {
        load()
        val img = PromptImage("img1", 448, 336, ByteArray(448 * 336 * 3))
        // fit() counts first (placeholder image), then the turn runs with HIGH detail: min(1024, catalog 512) == 512 both times.
        engine.countPromptTokens(listOf(PromptMessage("user", "Hi", listOf("img1"))), listOf(img.copy(rgb = null)))
        engine.generate("conv-1", listOf(PromptMessage("user", "Hi", listOf("img1"))), listOf(img), GenerationParams(), EngineTestFixtures.qwenThinking, false, ImageDetail.HIGH).toList()
        assertEquals(1, native.calls.count { it.startsWith("mmprojLoad") })
        assertEquals(0, native.calls.count { it == "mmprojFree" })
        // A thermal step-down between two image turns rebuilds the projector once, at the count the turn runs with.
        threads = 3
        engine.countPromptTokens(listOf(PromptMessage("user", "Hi", listOf("img1"))), listOf(img.copy(rgb = null)))
        gen(images = listOf(img)).toList()
        assertEquals(listOf("mmprojLoad(threads=4,maxTok=512)", "mmprojLoad(threads=3,maxTok=512)"), native.calls.filter { it.startsWith("mmprojLoad") })
    }

    @Test fun loadKeepsThePlannersEstimateCacheAndUnloadClearsIt() = runBlocking {
        load()
        val before = native.calls.count { it == "estimateCacheClear" }
        load()                                                        // model switch / reloadCurrent: no clear before the budget gate
        assertEquals(before, native.calls.count { it == "estimateCacheClear" })
        engine.unload()
        assertEquals(before + 1, native.calls.count { it == "estimateCacheClear" })
    }
}
