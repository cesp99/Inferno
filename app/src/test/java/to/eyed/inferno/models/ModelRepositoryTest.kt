package to.eyed.inferno.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ModelRepositoryTest {
    @get:Rule val perTest: Timeout = Timeout.seconds(40)


    // ---- fakes -------------------------------------------------------------------------------------------------

    private class FakeEngine : EngineAccess {
        override var loadedModelId: String? = null
        override var isGenerating = false
        override var loadedImageModelId: String? = null
        var unloads = 0; var clears = 0; var imageUnloads = 0
        override suspend fun unload() { unloads++; loadedModelId = null }
        override suspend fun clearEstimateCache() { clears++ }
        override suspend fun unloadImage() { imageUnloads++; loadedImageModelId = null }
    }

    private class FakePrefs : DownloadPrefs {
        override var allowMeteredDownloads = false
        override var selectedModelId: String? = null
        override suspend fun setAllowMeteredDownloads(allow: Boolean) { allowMeteredDownloads = allow }
        override suspend fun setSelectedModel(id: String?) { selectedModelId = id }
    }

    private class FakePlatform : DownloadPlatform {
        override var isMetered = false
        var serviceStarts = 0
        /** Simulates ForegroundServiceStartNotAllowedException: the start is refused (and counted separately). */
        var refuseStart = false
        var refusedStarts = 0
        var watchers = 0
        /** When set, the watch reports the current network during registration, like registerDefaultNetworkCallback. */
        var fireOnRegister = false
        var onAvailable: ((Boolean) -> Unit)? = null
        override fun startService(): Boolean { if (refuseStart) { refusedStarts++; return false }; serviceStarts++; return true }
        override fun watchNetwork(onAvailable: (metered: Boolean) -> Unit): AutoCloseable {
            watchers++; this.onAvailable = onAvailable
            if (fireOnRegister) onAvailable(isMetered)
            return AutoCloseable { watchers--; this.onAvailable = null }
        }
    }

    /** Serves the fake HF files with Range support; [effect] / [code] override the next response once. */
    private inner class FileDispatcher : Dispatcher() {
        val bodies = HashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var effect: SocketEffect? = null
        var code: Int? = null
        var throttle = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val bytes = bodies[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
            code?.let { code = null; return MockResponse.Builder().code(it).build() }
            val b = MockResponse.Builder().addHeader("ETag", "\"e1\"")
            val range = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            if (range != null && range in 1 until bytes.size) {
                b.code(206).addHeader("Content-Range", "bytes $range-${bytes.size - 1}/${bytes.size}").body(Buffer().write(bytes, range, bytes.size - range))
            } else b.code(200).body(Buffer().write(bytes))
            effect?.let { b.onResponseBody(it); effect = null }
            if (throttle) b.throttleBody(4 * 1024, 50, TimeUnit.MILLISECONDS)
            return b.build()
        }
    }

    // ---- fixture -----------------------------------------------------------------------------------------------

    private val server = MockWebServer()
    private val dispatcher = FileDispatcher()
    private lateinit var root: File
    private lateinit var files: ModelFiles
    private val engine = FakeEngine()
    private val prefs = FakePrefs()
    private val platform = FakePlatform()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: ModelRepository
    private lateinit var catalog: List<CatalogModel>
    private val states = CopyOnWriteArrayList<Pair<String, DownloadState>>()
    private var freeBytes = 100L shl 30

    private fun model(id: String, tier: ModelTier, textSize: Int, mmprojSize: Int, seed: Int): CatalogModel {
        val text = Gguf.bytes(totalSize = textSize, seed = seed)
        val mmproj = Gguf.bytes(arch = "clip", totalSize = mmprojSize, seed = seed + 100)
        dispatcher.bodies["/$id/text.gguf"] = text
        dispatcher.bodies["/$id/mmproj.gguf"] = mmproj
        return ModelCatalog.recommended.copy(
            id = id, displayName = id.uppercase(), tier = tier, recommended = false,
            text = ModelFileSpec("text.gguf", server.url("/$id/text.gguf").toString(), text.size.toLong(), sha256Hex(text), Quant.Q4_0),
            mmproj = ModelFileSpec("mmproj.gguf", server.url("/$id/mmproj.gguf").toString(), mmproj.size.toLong(), sha256Hex(mmproj), Quant.Q8_0),
        )
    }

    @Before fun setUp() {
        server.dispatcher = dispatcher
        server.start()
        root = tempDir("repo")
        catalog = listOf(
            model("bal", ModelTier.BALANCED, 96 * 1024, 32 * 1024, 1),
            model("fast", ModelTier.FASTEST, 48 * 1024, 16 * 1024, 2),
        )
        files = ModelFiles(root, null, { freeBytes to (128L shl 30) }, catalog)
        val downloader = ModelDownloader(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), files, log = {})
        repo = ModelRepository(files, downloader, engine, prefs, platform, scope, catalog)
        runBlocking { repo.refreshNow() }
    }

    @After fun tearDown() { scope.cancel(); server.close(); root.deleteRecursively() }

    private var queueError: Throwable? = null
    private fun runQueue(): Job = scope.launch {
        try { repo.runQueue { id, s -> states += id to s } } catch (e: Throwable) { queueError = e }
    }

    /** Synchronous derivation (what `entries` will show once the combine has run). */
    private fun state(id: String) = repo.downloadState(id).also { assertNull("queue error", queueError) }

    private suspend fun await(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!cond()) delay(10) } }
        catch (e: Exception) { throw AssertionError("timed out waiting for $what; last states: ${states.takeLast(5)}", e) }
    }

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test fun entriesAreSortedByTierThenCatalogOrderWithImportsLast() = runBlocking {
        await("entries") { repo.entries.value.size == 2 }
        assertEquals(listOf("fast", "bal"), repo.entries.value.map { it.id })
        assertTrue(repo.entries.value.all { it.download == DownloadState.NotDownloaded && !it.isDownloaded })
        val imported = files.importFrom({ Gguf.bytes(totalSize = 4096).inputStream() }, null, "Mine", false, null)
        repo.refreshNow()
        await("import row") { repo.entries.value.size == 3 }
        assertEquals(listOf("fast", "bal", imported.id), repo.entries.value.map { it.id })
        assertEquals("Unknown (imported)", repo.entries.value.last().license)
        assertEquals(setOf(ImageModelCatalog.dreamShaper.id, ImageModelCatalog.sdxs.id), repo.imageDownloads.value.keys)
        assertTrue(repo.imageDownloads.value.values.all { it == DownloadState.NotDownloaded })
    }

    @Test fun downloadRunsTextThenProjectorAndLandsAsDownloaded() = runBlocking {
        repo.startDownload("bal")
        assertEquals(DownloadState.Queued, state("bal"))
        assertEquals(1, platform.serviceStarts)
        val q = runQueue()
        await("Downloaded") { state("bal") == DownloadState.Downloaded }
        q.join()
        assertTrue(repo.isQueueEmpty)
        await("entries reflect it") { repo.entries.value.first { it.id == "bal" }.let { it.download == DownloadState.Downloaded && it.isDownloaded } }
        val local = repo.local("bal")!!
        assertTrue(local.hasVision); assertEquals(catalog[0].totalBytes, local.totalBytes)
        val seq = states.filter { it.first == "bal" }.map { it.second }
        assertTrue(seq.any { it is DownloadState.Downloading && it.fileIndex == 1 && it.fileCount == 2 })
        assertTrue(seq.any { it is DownloadState.Downloading && it.fileIndex == 2 && it.fileCount == 2 })
        assertTrue(seq.any { it is DownloadState.Verifying })
        assertEquals(DownloadState.Downloaded, seq.last())
        val totals = seq.filterIsInstance<DownloadState.Downloading>().map { it.totalBytes }.toSet()
        assertEquals(setOf(catalog[0].totalBytes), totals)
        assertTrue(repo.storage.value.modelsBytes >= catalog[0].totalBytes)
        // Starting again is a no-op.
        repo.startDownload("bal")
        assertEquals(1, platform.serviceStarts)
    }

    @Test fun refusesWhenFreeSpaceIsBelowRemainingPlusMargin() = runBlocking {
        freeBytes = ModelRepository.FREE_SPACE_MARGIN + catalog[0].totalBytes - 1
        var notice: String? = null
        val collector = scope.launch { notice = repo.notice.first() }
        delay(50)
        repo.startDownload("bal")
        await("notice") { notice != null }
        collector.join()
        assertEquals("Not enough storage for this download", notice)
        assertEquals(DownloadState.NotDownloaded, state("bal"))
        assertEquals(0, platform.serviceStarts)
    }

    @Test fun meteredNetworkAsksFirst() = runBlocking {
        platform.isMetered = true
        repo.startDownload("fast")
        assertEquals(ModelRepository.MeteredRequest("fast", catalog[1].totalBytes), repo.meteredConfirm.value)
        assertEquals(DownloadState.NotDownloaded, state("fast"))
        assertEquals(0, platform.serviceStarts)
        repo.confirmMetered("fast", always = true)
        await("queued") { state("fast") == DownloadState.Queued }
        assertNull(repo.meteredConfirm.value)
        assertTrue(prefs.allowMeteredDownloads)
        // Allowed in prefs: no prompt for the next one.
        repo.startDownload("bal")
        assertNull(repo.meteredConfirm.value)
        assertEquals(DownloadState.Queued, state("bal"))
    }

    @Test fun waitForWifiResumesOnUnmeteredNetwork() = runBlocking {
        platform.isMetered = true
        repo.onAppForeground(true)
        repo.startDownload("fast")
        repo.waitForWifi("fast")
        assertNull(repo.meteredConfirm.value)
        assertEquals(1, platform.watchers)
        platform.onAvailable!!(true)
        assertEquals("still metered: stays put", DownloadState.NotDownloaded, state("fast"))
        platform.isMetered = false
        platform.onAvailable!!(false)
        assertEquals(DownloadState.Queued, state("fast"))
        assertEquals(0, platform.watchers)
    }

    @Test fun cancelPausesKeepsPartAndResumeSendsRange() = runBlocking {
        dispatcher.throttle = true
        repo.startDownload("bal")
        val q = runQueue()
        await("some bytes") { (state("bal") as? DownloadState.Downloading)?.let { it.downloadedBytes > 0 } == true }
        repo.cancelDownload("bal")
        q.join()
        val paused = state("bal")
        assertTrue("$paused", paused is DownloadState.Paused && paused.downloadedBytes > 0 && paused.reason == null)
        assertEquals(catalog[0].totalBytes, (paused as DownloadState.Paused).totalBytes)
        assertTrue(files.partFile(files.textFile(catalog[0])).exists())
        assertTrue(repo.storage.value.partialBytes > 0)

        dispatcher.throttle = false
        dispatcher.requests.clear()
        repo.startDownload("bal")
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("bal"))
        assertNotNull("resume must use Range", dispatcher.requests.first().headers["Range"])
        assertEquals("\"e1\"", dispatcher.requests.first().headers["If-Range"])
    }

    @Test fun discardPartialGoesBackToNotDownloaded() = runBlocking {
        files.partFile(files.textFile(catalog[1])).makeSparseGguf(5000)
        repo.refreshNow()
        assertEquals(DownloadState.Paused(5000, catalog[1].totalBytes, null), state("fast"))
        repo.discardPartial("fast")
        await("discarded") { state("fast") == DownloadState.NotDownloaded }
        assertFalse(files.partFile(files.textFile(catalog[1])).exists())
    }

    @Test fun systemTimeoutPausesEverythingAndForegroundRestarts() = runBlocking {
        dispatcher.throttle = true
        repo.startDownload("bal"); repo.startDownload("fast")
        val q = runQueue()
        await("transferring") { state("bal") is DownloadState.Downloading }
        repo.pauseAll("Paused by the system")
        q.join()
        assertEquals("Paused by the system", (state("bal") as DownloadState.Paused).reason)
        assertEquals("Paused by the system", (state("fast") as DownloadState.Paused).reason)
        assertTrue(repo.isQueueEmpty)
        val starts = platform.serviceStarts
        repo.onAppForeground(false)
        assertEquals(starts, platform.serviceStarts)
        repo.onAppForeground(true)
        assertTrue(platform.serviceStarts > starts)
        assertEquals(DownloadState.Queued, state("bal")); assertEquals(DownloadState.Queued, state("fast"))
        dispatcher.throttle = false
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("bal")); assertEquals(DownloadState.Downloaded, state("fast"))
    }

    @Test fun networkLossPausesWithReasonAndReconnectResumes() = runBlocking {
        dispatcher.effect = SocketEffect.CloseSocket()
        repo.onAppForeground(true)
        repo.startDownload("fast")
        runQueue().join()
        val paused = state("fast") as DownloadState.Paused
        assertEquals("No connection", paused.reason)
        assertTrue(paused.downloadedBytes > 0)
        assertEquals(1, platform.watchers)
        platform.onAvailable!!(false)
        assertEquals(DownloadState.Queued, state("fast"))
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("fast"))
        assertEquals(0, platform.watchers)
    }

    @Test fun backgroundNetworkCallbackNeverStartsTheServiceForegroundDoes() = runBlocking {
        // Android 12+: startForegroundService from a ConnectivityManager callback while backgrounded throws. The
        // reconnect must therefore only be remembered; ON_START restarts the queue.
        platform.fireOnRegister = true
        dispatcher.effect = SocketEffect.CloseSocket()
        repo.startDownload("fast")
        runQueue().join()
        assertEquals("No connection", (state("fast") as DownloadState.Paused).reason)
        assertEquals(1, platform.watchers)
        assertEquals("registration replay / background reconnect must not start the FGS", 1, platform.serviceStarts)
        platform.onAvailable!!(false)
        assertEquals(1, platform.serviceStarts)
        assertTrue(state("fast") is DownloadState.Paused)

        repo.onAppForeground(true)
        assertEquals(2, platform.serviceStarts)
        assertEquals(DownloadState.Queued, state("fast"))
        assertEquals(0, platform.watchers)
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("fast"))

        // Foregrounded: the callback restarts right away.
        repo.onAppForeground(false); repo.onAppForeground(true)
        platform.isMetered = true
        repo.startDownload("bal"); repo.waitForWifi("bal")
        platform.isMetered = false
        platform.onAvailable!!(false)
        assertEquals(DownloadState.Queued, state("bal"))
        assertEquals(3, platform.serviceStarts)
    }

    @Test fun systemPauseDoesNotArmTheNetworkWatch() = runBlocking {
        // onTimeout: the dataSync budget is exhausted, a reconnect could not start the service anyway.
        platform.fireOnRegister = true
        dispatcher.throttle = true
        repo.startDownload("bal"); repo.startDownload("fast")
        val q = runQueue()
        await("transferring") { state("bal") is DownloadState.Downloading }
        val starts = platform.serviceStarts
        repo.pauseAll("Paused by the system")
        q.join()
        assertEquals(0, platform.watchers)
        assertEquals(starts, platform.serviceStarts)
        assertEquals("Paused by the system", (state("bal") as DownloadState.Paused).reason)
        assertEquals("Paused by the system", (state("fast") as DownloadState.Paused).reason)
        repo.onAppForeground(true)
        assertEquals(DownloadState.Queued, state("bal")); assertEquals(DownloadState.Queued, state("fast"))
        dispatcher.throttle = false
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("bal")); assertEquals(DownloadState.Downloaded, state("fast"))
    }

    @Test fun refusedServiceStartRollsBackAndForegroundRetries() = runBlocking {
        platform.refuseStart = true
        repo.startDownload("fast")
        assertEquals(1, platform.refusedStarts); assertEquals(0, platform.serviceStarts)
        assertEquals("Paused by the system", (state("fast") as DownloadState.Paused).reason)
        assertTrue(repo.isQueueEmpty)
        platform.refuseStart = false
        repo.onAppForeground(true)
        assertEquals(1, platform.serviceStarts)
        assertEquals(DownloadState.Queued, state("fast"))
        runQueue().join()
        assertEquals(DownloadState.Downloaded, state("fast"))
    }

    @Test fun storageGateDropsAnAutoResumedIdInsteadOfRetryingForever() = runBlocking {
        val notices = CopyOnWriteArrayList<String>()
        val collector = scope.launch { repo.notice.collect { notices += it } }
        delay(50)
        platform.isMetered = true
        repo.onAppForeground(true)
        repo.startDownload("bal"); repo.waitForWifi("bal")
        assertEquals(1, platform.watchers)
        freeBytes = ModelRepository.FREE_SPACE_MARGIN + catalog[0].totalBytes - 1
        platform.isMetered = false
        platform.onAvailable!!(false)
        await("notice") { notices.size == 1 }
        assertEquals("Not enough storage for this download", notices.single())
        assertEquals(DownloadState.Failed("Not enough storage", true), state("bal"))
        assertEquals("watch closed, no more callbacks", 0, platform.watchers)
        assertEquals(0, platform.serviceStarts)
        collector.cancel()
    }

    @Test fun deleteUnloadsAnImageModelHeldBySdcpp() = runBlocking {
        val ds = ImageModelCatalog.dreamShaper.id
        engine.loadedImageModelId = ds
        repo.delete(ImageModelCatalog.sdxs.id)
        assertEquals(0, engine.imageUnloads)
        repo.delete(ds)
        assertEquals(1, engine.imageUnloads); assertNull(engine.loadedImageModelId)
        engine.loadedImageModelId = ds
        repo.deleteAll()
        assertEquals(2, engine.imageUnloads)
    }

    @Test fun discardPartialLeavesTheSharedTaesdToAQueuedImageModel() = runBlocking {
        val ds = ImageModelCatalog.dreamShaper; val sd = ImageModelCatalog.sdxs
        val taesdPart = files.partFile(files.fileFor(ImageModelCatalog.taesd.toDownload(ImageModelCatalog.TAESD_DIR_ID)))
        val sdPart = files.partFile(files.fileFor(sd.file.toDownload(sd.id)))
        sdPart.makeSparseGguf(4096); taesdPart.makeSparseGguf(4096)
        repo.refreshNow()
        assertTrue(state(sd.id) is DownloadState.Paused)
        repo.startDownload(ds.id)                    // queued (the queue is not running), about to need the TAESD file
        assertEquals(DownloadState.Queued, state(ds.id))
        repo.discardPartial(sd.id)
        await("discarded") { state(sd.id) == DownloadState.NotDownloaded }
        assertFalse(sdPart.exists())
        assertTrue("shared TAESD partial belongs to the queued transfer", taesdPart.exists())
        repo.cancelDownload(ds.id)               // nothing else wants the TAESD file now: the discard takes it too
        sdPart.makeSparseGguf(4096); repo.refreshNow()
        repo.discardPartial(sd.id)
        await("discarded with taesd") { !taesdPart.exists() }
    }

    @Test fun notFoundFailsWithoutPartial() = runBlocking {
        dispatcher.code = 404
        repo.startDownload("fast")
        runQueue().join()
        assertEquals(DownloadState.Failed("File not found (catalog out of date)", false), state("fast"))
        assertFalse(files.partFile(files.textFile(catalog[1])).exists())
        repo.startDownload("fast")
        runQueue().join()
        assertEquals("retry after Failed", DownloadState.Downloaded, state("fast"))
    }

    @Test fun deleteRefusesWhileGeneratingAndUnloadsOtherwise() = runBlocking {
        repo.startDownload("fast"); runQueue().join()
        engine.loadedModelId = "fast"; engine.isGenerating = true
        prefs.selectedModelId = "fast"
        var notice: String? = null
        val collector = scope.launch { notice = repo.notice.first() }
        delay(50)
        repo.delete("fast")
        collector.join()
        assertEquals("Stop the current answer first", notice)
        assertEquals(DownloadState.Downloaded, state("fast")); assertEquals(0, engine.unloads)

        engine.isGenerating = false
        repo.delete("fast")
        assertEquals(1, engine.unloads); assertEquals(1, engine.clears)
        assertNull(prefs.selectedModelId)
        assertFalse(files.dirFor("fast").exists())
        assertEquals(DownloadState.NotDownloaded, state("fast"))
        assertNull(repo.local("fast"))

        // Deleting a model that is not loaded leaves the engine alone but still clears the estimate cache.
        repo.startDownload("bal"); runQueue().join()
        engine.loadedModelId = "fast"; prefs.selectedModelId = "bal"
        repo.delete("bal")
        assertEquals(1, engine.unloads); assertEquals(2, engine.clears); assertNull(prefs.selectedModelId)
    }

    @Test fun deleteWhileDownloadingCancelsFirst() = runBlocking {
        dispatcher.throttle = true
        repo.startDownload("bal")
        val q = runQueue()
        await("transferring") { state("bal") is DownloadState.Downloading }
        repo.delete("bal")
        q.join()
        assertFalse(files.dirFor("bal").exists())
        assertEquals(DownloadState.NotDownloaded, state("bal"))
    }
}
