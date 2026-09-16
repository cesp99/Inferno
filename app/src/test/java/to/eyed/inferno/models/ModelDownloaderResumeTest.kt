package to.eyed.inferno.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Spec 5.3 resume contract: 206 / 200 / 416 / 401 / 403 / 404 / size mismatch / ENOSPC / network loss / corrupt. */
class ModelDownloaderResumeTest {
    @get:Rule val perTest: Timeout = Timeout.seconds(40)

    private val server = MockWebServer()
    private lateinit var root: File
    private lateinit var files: ModelFiles
    private lateinit var downloader: ModelDownloader
    private val body = Gguf.bytes(totalSize = 64 * 1024)
    private lateinit var spec: DownloadableFile
    private lateinit var dest: File
    private val part get() = files.partFile(dest)
    private val json get() = files.partJsonFile(dest)

    @Before fun setUp() {
        server.start()
        root = tempDir("dl")
        files = testFiles(root)
        downloader = ModelDownloader(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), files, log = {})
        spec = DownloadableFile(server.url("/m/model.gguf").toString(), "model.gguf", body.size.toLong(), "m", sha256Hex(body))
        dest = files.fileFor(spec)
    }

    @After fun tearDown() { server.close(); root.deleteRecursively() }

    private fun response(code: Int, bytes: ByteArray, etag: String? = "\"v1\"") = MockResponse.Builder().code(code)
        .apply { etag?.let { addHeader("ETag", it) } }.body(Buffer().write(bytes))

    private fun partial(prefix: Int, etag: String? = "\"v1\"", withJson: Boolean = true) {
        part.parentFile!!.mkdirs()
        part.writeBytes(body.copyOfRange(0, prefix))
        if (withJson) json.writeText("""{"url":"${spec.url}","etag":${etag?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},"expectedBytes":${body.size},"startedAt":1}""")
    }

    private fun download(onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }) = runBlocking { downloader.download(spec, dest, onProgress) }

    private fun expectFailure(resumable: Boolean, message: String, block: () -> Unit): DownloadException {
        try { block() } catch (e: DownloadException) {
            assertEquals(message, e.message); assertEquals("resumable", resumable, e.resumable); return e
        }
        fail("expected DownloadException"); throw AssertionError()
    }

    @Test fun freshDownloadVerifiesAndRenames() {
        server.enqueue(response(200, body).build())
        val ticks = ArrayList<Triple<Long, Long, Long>>()
        download { d, t, b -> ticks += Triple(d, t, b) }
        assertArrayEquals(body, dest.readBytes())
        assertFalse(part.exists()); assertFalse(json.exists())
        assertEquals(0L, ticks.first().first)
        assertEquals(body.size.toLong(), ticks.last().first)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b.first >= a.first })
        val req = server.takeRequest()
        assertNull(req.headers["Range"]); assertTrue(req.headers["User-Agent"]!!.startsWith("Inferno/"))
    }

    @Test fun resume206AppendsWithRangeAndIfRange() {
        val half = body.size / 2
        partial(half)
        server.enqueue(response(206, body.copyOfRange(half, body.size)).addHeader("Content-Range", "bytes $half-${body.size - 1}/${body.size}").build())
        download()
        val req = server.takeRequest()
        assertEquals("bytes=$half-", req.headers["Range"])
        assertEquals("\"v1\"", req.headers["If-Range"])
        assertArrayEquals(body, dest.readBytes())
        assertFalse(json.exists())
    }

    @Test fun resume200RestartsFromZero() {
        partial(body.size / 3)
        server.enqueue(response(200, body, etag = "\"v2\"").build())
        download()
        assertEquals("bytes=${body.size / 3}-", server.takeRequest().headers["Range"])
        assertArrayEquals(body, dest.readBytes())
    }

    @Test fun partWithoutJsonIsRestarted() {
        partial(body.size / 2, withJson = false)
        server.enqueue(response(200, body).build())
        download()
        assertNull("a .part of unknown provenance must not be resumed", server.takeRequest().headers["Range"])
        assertArrayEquals(body, dest.readBytes())
    }

    @Test fun completeSizedPartGoesStraightToVerification() {
        partial(body.size)
        download()
        assertArrayEquals(body, dest.readBytes())
        assertEquals("no request needed for a complete .part", 0, server.requestCount)
    }

    @Test fun unexpected416RestartsFromZeroOnce() {
        partial(body.size / 2)
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(response(200, body).build())
        download()
        assertEquals("bytes=${body.size / 2}-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
        assertArrayEquals(body, dest.readBytes())
        // A second 416 in a row is not retried forever.
        dest.delete(); partial(body.size / 2)
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(MockResponse.Builder().code(416).build())
        expectFailure(true, "Server keeps rejecting the resume") { download() }
    }

    @Test fun gatedRepoIsTerminalAndDropsThePartial() {
        for (code in listOf(401, 403)) {
            partial(100)
            server.enqueue(MockResponse.Builder().code(code).build())
            expectFailure(false, "This model requires accepting a license on Hugging Face") { download() }
            assertFalse(part.exists()); assertFalse(json.exists()); assertFalse(dest.exists())
        }
    }

    @Test fun notFoundIsTerminal() {
        server.enqueue(MockResponse.Builder().code(404).build())
        expectFailure(false, "File not found (catalog out of date)") { download() }
        assertFalse(part.exists()); assertFalse(json.exists())
    }

    @Test fun serverErrorKeepsThePartial() {
        partial(1000)
        server.enqueue(MockResponse.Builder().code(503).build())
        expectFailure(true, "Server error (503)") { download() }
        assertEquals(1000L, part.length()); assertTrue(json.exists())
    }

    @Test fun contentLengthMismatchIsTerminalBeforeWriting() {
        server.enqueue(response(200, body + byteArrayOf(1, 2, 3)).build())
        expectFailure(false, "Catalog size mismatch") { download() }
        assertFalse(part.exists()); assertFalse(json.exists())
    }

    @Test fun networkLossMidFilePausesAndThenResumes() {
        server.enqueue(response(200, body).onResponseBody(SocketEffect.CloseSocket()).build())
        expectFailure(true, "No connection") { download() }
        val got = part.length()
        assertTrue("partial kept ($got)", got in 1 until body.size.toLong()); assertTrue(json.exists())
        server.enqueue(response(206, body.copyOfRange(got.toInt(), body.size)).addHeader("Content-Range", "bytes $got-${body.size - 1}/${body.size}").build())
        download()
        server.takeRequest()
        assertEquals("bytes=$got-", server.takeRequest().headers["Range"])
        assertArrayEquals(body, dest.readBytes())
    }

    @Test fun missingMagicIsCorrupt() {
        val bogus = ByteArray(body.size) { 7 }
        spec = spec.copy(sha256 = null)
        server.enqueue(response(200, bogus).build())
        expectFailure(false, "Downloaded file is corrupt") { download() }
        assertFalse(part.exists()); assertFalse(dest.exists())
    }

    @Test fun sha256MismatchIsCorrupt() {
        spec = spec.copy(sha256 = "0".repeat(64))
        server.enqueue(response(200, body).build())
        expectFailure(false, "Downloaded file is corrupt") { download() }
        assertFalse(dest.exists())
    }

    @Test fun safetensorsSkipsTheGgufMagic() {
        val st = ByteArray(4096).also { it[8] = '{'.code.toByte() }
        spec = DownloadableFile(server.url("/t/taesd.safetensors").toString(), "taesd.safetensors", st.size.toLong(), "taesd")
        dest = files.fileFor(spec)
        server.enqueue(response(200, st).build())
        download()
        assertArrayEquals(st, dest.readBytes())
        server.enqueue(response(200, ByteArray(4096)).build())
        dest.delete()
        expectFailure(false, "Downloaded file is corrupt") { download() }
    }

    @Test fun enospcMapsToNotEnoughStorage() {
        val e = ModelDownloader.mapIo(IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals("Not enough storage", e.message); assertTrue(e.resumable)
        assertEquals("No connection", ModelDownloader.mapIo(java.net.UnknownHostException("huggingface.co")).message)
    }

    @Test fun partJsonExistsBeforeTheFirstByte() = runBlocking {
        server.enqueue(response(200, body).bodyDelay(600, TimeUnit.MILLISECONDS).build())
        val job = async(Dispatchers.IO) { downloader.download(spec, dest, { _, _, _ -> }) }
        server.takeRequest()
        assertTrue(json.exists())
        assertTrue(part.length() == 0L)
        job.await()
        assertArrayEquals(body, dest.readBytes())
    }

    @Test fun cancelKeepsPartAndJson() = runBlocking {
        server.enqueue(response(200, body).throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS).build())
        var firstTick = false
        val job = async(Dispatchers.IO) { downloader.download(spec, dest, { d, _, _ -> if (d > 0) firstTick = true }) }
        withTimeout(5_000) { while (!firstTick) delay(10) }
        job.cancel()
        runCatching { job.await() }
        assertTrue(part.exists() && part.length() > 0)
        assertTrue(json.exists()); assertFalse(dest.exists())
    }

    @Test fun cancelDuringAStalledReadIsImmediateAndIsNotANetworkLoss() = runBlocking {
        // 8 kB then nothing for 30 s: without the call bound to the job, cancel would wait for the 5 s readTimeout.
        server.enqueue(response(200, body).throttleBody(8 * 1024, 30_000, TimeUnit.MILLISECONDS).build())
        val job = async(Dispatchers.IO) { downloader.download(spec, dest, { _, _, _ -> }) }
        withTimeout(5_000) { while (part.length() == 0L) delay(10) }   // first chunk landed, the read now stalls
        val t0 = System.nanoTime()
        job.cancel()
        val outcome = runCatching { job.await() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 2_000)
        assertTrue("expected a CancellationException, got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(part.exists() && part.length() > 0); assertTrue(json.exists())
    }

    @Test fun orphanedJsonNextToACompleteFileIsRemoved() {
        // Process death between the rename and json.delete(): dest is complete, the json is stale.
        dest.parentFile!!.mkdirs(); dest.writeBytes(body)
        json.writeText("{}")
        download()
        assertArrayEquals(body, dest.readBytes())
        assertFalse(json.exists())
        assertEquals(0, server.requestCount)
    }

    @Test fun progressTicksAreFrequent() = runBlocking {
        server.enqueue(response(200, body).throttleBody(4 * 1024, 60, TimeUnit.MILLISECONDS).build())
        val times = ArrayList<Long>()
        downloader.download(spec, dest, { _, _, _ -> times += System.nanoTime() })
        assertTrue("expected several ticks over ~1 s, got ${times.size}", times.size >= 4)
        val gaps = times.zipWithNext { a, b -> (b - a) / 1_000_000 }
        assertTrue("max gap ${gaps.maxOrNull()} ms", gaps.drop(1).dropLast(1).all { it <= 250 + 150 })
    }
}
