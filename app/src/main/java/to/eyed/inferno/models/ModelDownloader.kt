package to.eyed.inferno.models

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadException(message: String, val resumable: Boolean) : Exception(message)

/**
 * Resumable HTTP download of one [DownloadableFile] into `dest`. The transfer goes to `dest.part`, its
 * bookkeeping to `dest.part.json` (written before the first byte); the complete file is renamed into place only
 * after size (+ sha256 when known) + magic verification, so a file that exists at `dest` is always usable.
 *
 * Resume contract: `Range: bytes=<partSize>-` + `If-Range: <etag>`; 206 appends, 200 restarts from zero (the
 * upstream file changed or the server ignores ranges), 416 with a complete-sized `.part` goes straight to
 * verification, 401/403 (gated repo) and 404 are terminal and drop the partial, other 4xx/5xx keep it.
 * Cancelling the calling coroutine keeps `.part` + `.part.json` (the caller shows Paused).
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val files: ModelFiles,
    private val userAgent: String = "Inferno/0.1",
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    @Serializable
    data class PartMeta(val url: String, val etag: String? = null, val expectedBytes: Long, val startedAt: Long)

    /**
     * @param onProgress (downloadedBytes, totalBytes, bytesPerSec), at most every [PROGRESS_INTERVAL_MS] while
     *   transferring, plus once at the start and once at the end of the transfer.
     * @param onVerifying called once when the transfer is complete and hashing starts (the UI shows "Verifying").
     */
    suspend fun download(
        spec: DownloadableFile, dest: File,
        onProgress: (downloaded: Long, total: Long, bps: Long) -> Unit,
        onVerifying: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val part = files.partFile(dest)
        val json = files.partJsonFile(dest)
        if (dest.isFile && dest.length() == spec.sizeBytes) {
            json.delete()   // orphaned when the process died between the rename and the delete below
            onProgress(spec.sizeBytes, spec.sizeBytes, 0); return@withContext
        }
        dest.parentFile?.mkdirs()
        try {
            var restarted = false
            while (true) {
                val outcome = transfer(spec, part, json, onProgress)
                if (outcome == Outcome.RESTART && !restarted) { restarted = true; continue }
                if (outcome == Outcome.RESTART) throw DownloadException("Server keeps rejecting the resume", resumable = true)
                break
            }
            onVerifying()
            verify(spec, part)
            // Rename first: dying between the two steps must leave `dest` (short-circuited above), never a
            // complete-sized .part without its json, which transfer() would treat as unknown and redownload.
            if (!part.renameTo(dest)) throw DownloadException("Could not move the file into place", resumable = true)
            json.delete()
            onProgress(spec.sizeBytes, spec.sizeBytes, 0)
        } catch (e: DownloadException) {
            if (!e.resumable) { part.delete(); json.delete() }
            log("${spec.fileName}: ${e.message} (resumable=${e.resumable})")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ensureActive()          // call.cancel() makes read() throw; report the cancellation, not "No connection"
            val mapped = mapIo(e)
            if (!mapped.resumable) { part.delete(); json.delete() }
            log("${spec.fileName}: ${e.javaClass.simpleName}: ${e.message} -> ${mapped.message}")
            throw mapped
        }
    }

    private enum class Outcome { DONE, RESTART }

    private suspend fun transfer(spec: DownloadableFile, part: File, json: File, onProgress: (Long, Long, Long) -> Unit): Outcome {
        var meta = readMeta(json)
        // A .part without its json (or for another URL) has unknown provenance: treat it as 0 bytes.
        if (meta == null || meta.url != spec.url || part.length() > spec.sizeBytes) {
            truncate(part)
            meta = PartMeta(url = spec.url, etag = null, expectedBytes = spec.sizeBytes, startedAt = System.currentTimeMillis())
            writeMeta(json, meta)
        }
        var partSize = part.length()
        if (partSize == spec.sizeBytes) return Outcome.DONE   // e.g. process death between transfer end and verification

        val request = Request.Builder().url(spec.url).header("User-Agent", userAgent).apply {
            if (partSize > 0) {
                header("Range", "bytes=$partSize-")
                meta.etag?.let { header("If-Range", it) }
            }
        }.build()
        log("GET ${spec.fileName} from $partSize" + (if (partSize > 0) " (Range" + (if (meta.etag != null) " + If-Range)" else ")") else ""))

        val call = client.newCall(request)
        // source.read() is a blocking socket read that only notices cancellation when data arrives (or readTimeout
        // fires, 60 s). Cancel the call itself when the job is cancelled so a pause on a stalled link is immediate.
        return coroutineScope {
            val abort = launch { try { awaitCancellation() } finally { if (!this@coroutineScope.isActive) call.cancel() } }
            try { readBody(spec, part, json, meta, partSize, call, onProgress) } finally { abort.cancel() }
        }
    }

    /** One HTTP exchange: status handling, then the body appended to `part`. Returns RESTART when the resume was refused. */
    private suspend fun readBody(
        spec: DownloadableFile, part: File, json: File, meta: PartMeta, startSize: Long, call: Call,
        onProgress: (Long, Long, Long) -> Unit,
    ): Outcome {
        var partSize = startSize
        call.await().use { response ->
            val code = response.code
            val etag = response.header("ETag")
            when {
                code == 206 -> {
                    // Some CDNs answer 206 for a full-file range; trust Content-Range when present.
                    val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != null && start != partSize) {
                        if (start != 0L) throw DownloadException("Server resumed at the wrong offset", resumable = true)
                        truncate(part); partSize = 0
                    }
                    checkTotal(spec, response, partSize)
                    if (etag != meta.etag) writeMeta(json, meta.copy(etag = etag))
                }
                code == 200 -> {
                    if (partSize > 0) log("${spec.fileName}: server ignored the range / file changed, restarting")
                    truncate(part); partSize = 0
                    checkTotal(spec, response, 0)
                    writeMeta(json, PartMeta(spec.url, etag, spec.sizeBytes, System.currentTimeMillis()))
                }
                code == 416 -> {
                    if (partSize == spec.sizeBytes) return Outcome.DONE
                    // Our partial is bigger than the upstream file now: it is not the file we started.
                    truncate(part); writeMeta(json, meta.copy(etag = null)); return Outcome.RESTART
                }
                code == 401 || code == 403 -> throw DownloadException("This model requires accepting a license on Hugging Face", resumable = false)
                code == 404 -> throw DownloadException("File not found (catalog out of date)", resumable = false)
                else -> throw DownloadException("Server error ($code)", resumable = true)
            }
            val source = response.body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var downloaded = partSize
            var lastTick = System.nanoTime()
            var lastTickBytes = downloaded
            var bps = 0L
            onProgress(downloaded, spec.sizeBytes, 0)
            FileOutputStream(part, true).use { out ->
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    if (downloaded > spec.sizeBytes) throw DownloadException("Catalog size mismatch", resumable = false)
                    val now = System.nanoTime()
                    val elapsed = now - lastTick
                    if (elapsed >= PROGRESS_INTERVAL_MS * 1_000_000L) {
                        bps = (downloaded - lastTickBytes) * 1_000_000_000L / elapsed
                        lastTick = now; lastTickBytes = downloaded
                        onProgress(downloaded, spec.sizeBytes, bps)
                    }
                    coroutineContext.ensureActive()
                }
                out.flush()
                out.fd.sync()       // the rename must never race ahead of the data on a power loss
            }
            coroutineContext.ensureActive()     // a cancelled call may end the stream early: that is a pause, not a network loss
            if (downloaded != spec.sizeBytes) throw DownloadException("No connection", resumable = true)   // stream ended early
            onProgress(downloaded, spec.sizeBytes, bps)
        }
        return Outcome.DONE
    }

    private fun checkTotal(spec: DownloadableFile, response: Response, partSize: Long) {
        val len = response.header("Content-Length")?.toLongOrNull() ?: return
        if (len < 0) return
        if (len + partSize != spec.sizeBytes) throw DownloadException("Catalog size mismatch", resumable = false)
    }

    private fun verify(spec: DownloadableFile, part: File) {
        if (part.length() != spec.sizeBytes) throw DownloadException("Downloaded file is corrupt", resumable = false)
        val magicOk = when {
            spec.isGguf -> files.isGguf(part)
            spec.isSafetensors -> isSafetensors(part)
            else -> true
        }
        if (!magicOk) throw DownloadException("Downloaded file is corrupt", resumable = false)
        spec.sha256?.let { expected ->
            val actual = sha256Hex(part)
            if (!actual.equals(expected, ignoreCase = true)) throw DownloadException("Downloaded file is corrupt", resumable = false)
        }
    }

    /** safetensors = u64 LE header length followed by a JSON object; enough to reject an HTML error page saved as a file. */
    private fun isSafetensors(f: File): Boolean = runCatching {
        f.inputStream().use { s -> val b = ByteArray(9); s.read(b) == 9 && b[8] == '{'.code.toByte() }
    }.getOrDefault(false)

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun truncate(part: File) { if (part.exists()) FileOutputStream(part, false).close() }

    private fun readMeta(json: File): PartMeta? = runCatching { jsonCodec.decodeFromString<PartMeta>(json.readText()) }.getOrNull()
    private fun writeMeta(json: File, meta: PartMeta) { json.writeText(jsonCodec.encodeToString(meta)) }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            // Resuming an already-cancelled continuation is a no-op: close the body then, or the connection leaks.
            override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, _, _ -> response.close() } }
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        })
        cont.invokeOnCancellation { cancel() }
    }

    companion object {
        const val TAG = "Inferno/ModelDownloader"
        const val PROGRESS_INTERVAL_MS = 250L
        private const val BUFFER_SIZE = 1 shl 20
        private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** ENOSPC is the only I/O failure that is not "the network went away"; both keep the partial. */
        fun mapIo(e: IOException): DownloadException {
            val msg = e.message.orEmpty()
            return when {
                msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true) -> DownloadException("Not enough storage", resumable = true)
                e is SocketTimeoutException || e is UnknownHostException || e is ConnectException || e is SocketException ||
                    e is SSLException || e is InterruptedIOException -> DownloadException("No connection", resumable = true)
                else -> DownloadException("No connection", resumable = true)
            }
        }

        /** Spec 5.3: 20 s connect, 60 s read, follow redirects (HF 302 -> CDN). */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
