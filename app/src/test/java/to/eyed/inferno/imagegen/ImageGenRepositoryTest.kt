package to.eyed.inferno.imagegen

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.sd.ImageEngineState
import to.eyed.inferno.sd.ImageGenEvent
import to.eyed.inferno.sd.ImageGenRequest
import to.eyed.inferno.sd.ImageModelSpec

@OptIn(ExperimentalCoroutinesApi::class)
class ImageGenRepositoryTest {

    // ---- fakes -------------------------------------------------------------------------------

    /** Records every cross-seam call in order so the single-job protocol can be asserted. */
    private val calls = mutableListOf<String>()

    private inner class FakeEngine(
        var stepMs: Long = 2_000,
        var failWith: String? = null,
        var loadError: String? = null,
    ) : ImageEngineApi {
        override val state = MutableStateFlow<ImageEngineState>(ImageEngineState.Idle)
        var lastRequest: ImageGenRequest? = null
        var generateFinished = false
        var generateCancelled = false

        override suspend fun load(spec: ImageModelSpec, modelPath: String, taesdPath: String) {
            calls += "load:${spec.id}"
            state.value = loadError?.let { ImageEngineState.Error(it) } ?: ImageEngineState.Ready(spec.id)
        }

        override fun generate(req: ImageGenRequest): Flow<ImageGenEvent> = flow {
            calls += "generate"
            lastRequest = req
            try {
                emit(ImageGenEvent.Loading)
                failWith?.let { emit(ImageGenEvent.Error(it)); return@flow }
                var elapsed = 500L
                delay(elapsed)
                for (s in 1..req.steps) {
                    delay(stepMs); elapsed += stepMs
                    emit(ImageGenEvent.Progress(s, req.steps, elapsed, if (req.previews) byteArrayOf(s.toByte()) else null))
                }
                emit(ImageGenEvent.Decoding)
                delay(1_000); elapsed += 1_000
                emit(ImageGenEvent.Done(PNG, req.width, req.height, if (req.seed < 0) 4242L else req.seed, elapsed))
                generateFinished = true
            } catch (e: CancellationException) {
                generateCancelled = true
                throw e
            }
        }

        override suspend fun unload() {
            calls += "unload"
            state.value = ImageEngineState.Idle
        }

        override fun estimateBytes(spec: ImageModelSpec, modelBytes: Long): Long = 2_000_000_000L
    }

    private inner class FakeGate : JobGate {
        var held = false
        override suspend fun <T> withJob(tag: String, block: suspend () -> T): T {
            calls += "job:$tag"
            held = true
            try { return block() } finally { held = false; calls += "job-release" }
        }
    }

    private inner class FakeStore : GeneratedImageStore {
        val saved = mutableListOf<Pair<ByteArray, GenerationMeta>>()
        override suspend fun save(png: ByteArray, meta: GenerationMeta): GenerationRecord {
            calls += "save"
            saved += png to meta
            return GenerationRecord(
                id = "rec-${saved.size}", modelId = meta.modelId, prompt = meta.prompt, negative = meta.negative,
                width = meta.width, height = meta.height, steps = meta.steps, seed = meta.seed, sampler = meta.sampler,
                totalMs = meta.totalMs, createdAt = meta.createdAt, path = "/x/${saved.size}.png",
            )
        }
    }

    private class FakeEtaStore(initial: Map<String, Float> = emptyMap()) : EtaStore {
        var values: Map<String, Float> = initial
        var saves = 0
        override suspend fun load() = values
        override suspend fun save(values: Map<String, Float>) { this.values = values; saves++ }
    }

    private class FakeFiles(val present: Set<String>, val taesd: Boolean = true) : ImageModelFiles {
        override fun modelPath(model: ImageCatalogModel) = if (model.id in present) "/m/${model.id}.gguf" else null
        override fun taesdPath() = if (taesd) "/m/taesd.safetensors" else null
    }

    private class Harness(
        val engine: FakeEngine,
        val gate: FakeGate,
        val store: FakeStore,
        val etaStore: FakeEtaStore,
        val repo: ImageGenRepository,
        val states: List<ImageGenUiState>,
    )

    private fun TestScope.harness(
        engine: FakeEngine = FakeEngine(),
        files: ImageModelFiles = FakeFiles(setOf(DS.id, SDXS.id)),
        etaStore: FakeEtaStore = FakeEtaStore(),
        budget: Long = Long.MAX_VALUE,
    ): Harness {
        val gate = FakeGate()
        val store = FakeStore()
        val repo = ImageGenRepository(
            engine = engine,
            files = files,
            coordinator = { calls += "releaseLlm" },
            jobGate = gate,
            store = store,
            etaStore = etaStore,
            // Not backgroundScope: advanceUntilIdle() only drives background tasks while foreground ones exist.
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
            memoryBudgetBytes = { budget },
            clock = { 1_000_000L },
        )
        val states = mutableListOf<ImageGenUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.state.toList(states) }
        return Harness(engine, gate, store, etaStore, repo, states)
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    fun missingFilesYieldNeedsDownloadWithoutTouchingTheEngine() = runTest {
        val h = harness(files = FakeFiles(present = emptySet()))
        assertNull(h.repo.generate(ImageGenParams(DS.id, "a cat")))
        assertEquals(ImageGenUiState.NeedsDownload(DS.id), h.repo.state.value)
        assertFalse(h.repo.isDownloaded(DS))
        assertTrue(calls.isEmpty())

        // A present model with a missing TAESD file is equally not usable.
        val h2 = harness(files = FakeFiles(present = setOf(DS.id), taesd = false))
        assertNull(h2.repo.generate(ImageGenParams(DS.id, "a cat")))
        assertEquals(ImageGenUiState.NeedsDownload(DS.id), h2.repo.state.value)
    }

    @Test
    fun happyPathFollowsTheSingleJobProtocolAndPersistsTheResult() = runTest {
        val h = harness()
        val job = h.repo.generate(ImageGenParams(DS.id, "a cat", ImageSizePreset.S512, steps = 4))
        assertNotNull(job)
        assertTrue(h.repo.isBusy)
        advanceUntilIdle()

        // Order: mutex first, LLM released inside it, load, generate, save, release.
        assertEquals(listOf("job:imagegen", "releaseLlm", "load:${DS.id}", "generate", "save", "job-release"), calls)
        assertFalse(h.repo.isBusy)

        val done = h.repo.state.value as ImageGenUiState.Done
        assertEquals("a cat", done.record.prompt)
        assertEquals(4242L, done.record.seed)
        assertEquals(512, done.record.width)
        assertEquals("LCM", done.record.sampler)
        assertEquals(1_000_000L, done.record.createdAt)
        assertTrue(PNG.contentEquals(h.store.saved.single().first))

        val req = h.engine.lastRequest!!
        assertEquals(4, req.steps)
        assertEquals(1.0f, req.cfgScale)
        assertEquals("", req.negativePrompt)
        assertTrue(req.previews)

        assertTrue(h.states.contains(ImageGenUiState.Loading))
        val gens = h.states.filterIsInstance<ImageGenUiState.Generating>()
        // Step 4 and Decoding carry the same (step, eta, preview) so StateFlow may conflate them.
        assertEquals(listOf(0, 1, 2, 3, 4), gens.map { it.step }.distinct())
        // ETA shrinks step by step and never goes negative.
        assertTrue(gens.zipWithNext().all { (a, b) -> b.etaSeconds <= a.etaSeconds })
        assertTrue(gens.all { it.etaSeconds >= 0 })
        // The preview is carried over into the Decoding state.
        assertNotNull(gens.last().previewPng)
        assertTrue(gens.last().isDecoding)
    }

    @Test
    fun etaIsSeededThenLearnsFromMeasuredStepsAndIsPersisted() = runTest {
        val h = harness()
        // Seeds: 4 x 13.5 + 3.8 decode + overhead 5.2 = 63 s, exactly the measured figure.
        assertEquals(63, h.repo.estimateSeconds(DS.id, ImageSizePreset.S512))
        assertEquals(12, h.repo.estimateSeconds(SDXS.id, ImageSizePreset.S512))
        // 384 px scales by pixel count (0.5625): 4 x 7.6 + 2.1 + 5.2 = 37.7 -> 38.
        assertEquals(38, h.repo.estimateSeconds(DS.id, ImageSizePreset.S384))
        // Steps are clamped to the catalog range.
        assertEquals(h.repo.estimateSeconds(DS.id, ImageSizePreset.S512, 8), h.repo.estimateSeconds(DS.id, ImageSizePreset.S512, 20))

        h.repo.generate(ImageGenParams(DS.id, "x", steps = 4))
        advanceUntilIdle()
        // Fake steps take 2 s: 13.5 * 0.7 + 2 * 0.3 = 10.05 s/step.
        assertEquals(10.05f, h.repo.eta.secondsPerStep(DS.id, 512), 0.01f)
        assertEquals(1, h.etaStore.saves)
        assertEquals(10.05f, h.etaStore.values[EtaModel.key(DS.id, 512)]!!, 0.01f)

        // A second repository restores the persisted value over the seed.
        val h2 = harness(etaStore = FakeEtaStore(h.etaStore.values))
        advanceUntilIdle()
        assertEquals(10.05f, h2.repo.eta.secondsPerStep(DS.id, 512), 0.01f)
        // ...and the 384 estimate follows the observed 512 speed rather than the seed.
        assertEquals(10.05f * 0.5625f, h2.repo.eta.secondsPerStep(DS.id, 384), 0.01f)
    }

    @Test
    fun oneStepModelLearnsFromTimeToFirstStep() = runTest {
        val h = harness(engine = FakeEngine(stepMs = 3_000))
        h.repo.generate(ImageGenParams(SDXS.id, "x"))
        advanceUntilIdle()
        val req = h.engine.lastRequest!!
        assertEquals(1, req.steps)
        assertFalse(req.previews)
        // Time to the first (only) step = 500 ms conditioning + 3000 ms: 6.9 * 0.7 + 3.5 * 0.3 = 5.88.
        assertEquals(5.88f, h.repo.eta.secondsPerStep(SDXS.id, 512), 0.01f)
    }

    @Test
    fun cancelAbortsTheRunReleasesTheGateAndSavesNothing() = runTest {
        val h = harness()
        h.repo.generate(ImageGenParams(DS.id, "x", steps = 4))
        advanceTimeBy(3_000)   // inside step 2
        assertTrue(h.gate.held)
        assertTrue(h.repo.state.value is ImageGenUiState.Generating)

        h.repo.cancelAndJoin()
        assertEquals(ImageGenUiState.Idle, h.repo.state.value)
        assertTrue(h.engine.generateCancelled)
        assertFalse(h.engine.generateFinished)
        assertFalse(h.gate.held)
        assertTrue(h.store.saved.isEmpty())
        assertFalse(h.repo.isBusy)
        assertEquals(0, h.etaStore.saves)

        // The engine keeps the model, so the next run skips the load.
        calls.clear()
        h.repo.generate(ImageGenParams(DS.id, "y", steps = 2))
        advanceUntilIdle()
        assertEquals(listOf("job:imagegen", "releaseLlm", "generate", "save", "job-release"), calls)
    }

    @Test
    fun engineErrorSurfacesAsErrorState() = runTest {
        val h = harness(engine = FakeEngine(failWith = "Not enough memory"))
        h.repo.generate(ImageGenParams(DS.id, "x"))
        advanceUntilIdle()
        assertEquals(ImageGenUiState.Error("Not enough memory"), h.repo.state.value)
        assertTrue(h.store.saved.isEmpty())
        assertFalse(h.gate.held)
        h.repo.reset()
        assertEquals(ImageGenUiState.Idle, h.repo.state.value)
    }

    @Test
    fun loadFailureSurfacesTheEngineMessage() = runTest {
        val h = harness(engine = FakeEngine(loadError = "bad gguf"))
        h.repo.generate(ImageGenParams(DS.id, "x"))
        advanceUntilIdle()
        assertEquals(ImageGenUiState.Error("bad gguf"), h.repo.state.value)
        assertFalse(calls.contains("generate"))
    }

    @Test
    fun refusesToLoadOverTheMemoryBudget() = runTest {
        // Estimate is 2.0 GB; 0.85 x 2.2 GB = 1.87 GB < 2.0 GB.
        val h = harness(budget = 2_200_000_000L)
        h.repo.generate(ImageGenParams(DS.id, "x"))
        advanceUntilIdle()
        val err = h.repo.state.value as ImageGenUiState.Error
        assertTrue(err.message.contains("memory"))
        assertFalse(calls.any { it.startsWith("load") })
        assertFalse(h.gate.held)
    }

    @Test
    fun switchingModelsUnloadsTheOtherOneFirst() = runTest {
        val h = harness()
        h.repo.generate(ImageGenParams(DS.id, "x"))
        advanceUntilIdle()
        calls.clear()
        h.repo.generate(ImageGenParams(SDXS.id, "y"))
        advanceUntilIdle()
        assertEquals(listOf("job:imagegen", "releaseLlm", "unload", "load:${SDXS.id}", "generate", "save", "job-release"), calls)
    }

    @Test
    fun secondGenerateWhileBusyIsIgnored() = runTest {
        val h = harness()
        assertNotNull(h.repo.generate(ImageGenParams(DS.id, "x")))
        advanceTimeBy(1_000)
        assertNull(h.repo.generate(ImageGenParams(SDXS.id, "y")))
        advanceUntilIdle()
        assertEquals(1, calls.count { it == "generate" })
        assertEquals(DS.id, (h.repo.state.value as ImageGenUiState.Done).record.modelId)
    }

    @Test
    fun unloadCancelsAndFreesTheEngine() = runTest {
        val h = harness()
        h.repo.generate(ImageGenParams(DS.id, "x"))
        advanceTimeBy(1_000)
        h.repo.unload()
        assertEquals(ImageGenUiState.Idle, h.repo.state.value)
        assertEquals(ImageEngineState.Idle, h.engine.state.value)
        assertTrue(calls.last() == "unload")
    }

    @Test
    fun defaultFilesRequireExactSizes() {
        val root = File.createTempFile("models", "").apply { delete(); mkdirs() }
        try {
            val files = DefaultImageModelFiles(root)
            assertNull(files.taesdPath())
            val taesd = File(File(root, ImageModelCatalog.TAESD_DIR_ID), ImageModelCatalog.taesd.fileName)
            taesd.parentFile!!.mkdirs()
            taesd.writeBytes(ByteArray(10))
            assertNull(files.taesdPath())            // torn download
            java.io.RandomAccessFile(taesd, "rw").use { it.setLength(ImageModelCatalog.taesd.sizeBytes) }
            assertEquals(taesd.absolutePath, files.taesdPath())
            assertNull(files.modelPath(SDXS))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun catalogIsConsistent() {
        assertEquals(2, ImageModelCatalog.models.size)
        for (m in ImageModelCatalog.models) {
            assertEquals(m.file.fileName, m.spec.textFile)
            assertEquals(ImageModelCatalog.taesd.fileName, m.spec.taesdFile)
            assertTrue(m.file.url.endsWith("/" + m.file.fileName))
            assertTrue(m.spec.steps in m.minSteps..m.maxSteps)
            assertEquals(m.spec.estSeconds, m.estSecondsBySize[512])
            assertTrue(m.license.isNotBlank())
        }
        assertEquals(ImageModelCatalog.dreamShaper, ImageModelCatalog.byId("dreamshaper-8-lcm-q8_0"))
        assertTrue(ImageModelCatalog.dreamShaper.stepsAdjustable)
        assertFalse(ImageModelCatalog.sdxs.stepsAdjustable)
    }

    private companion object {
        val DS = ImageModelCatalog.dreamShaper
        val SDXS = ImageModelCatalog.sdxs
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
