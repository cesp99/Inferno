package to.eyed.inferno.models

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A catalog text/projector file as the downloader sees it: stored under `files/models/<modelId>/`. */
fun ModelFileSpec.toDownload(modelId: String) = DownloadableFile(url = url, fileName = fileName, sizeBytes = sizeBytes, subdir = modelId, sha256 = sha256)

/** An image-model file (WP9b) as the downloader sees it: `files/models/<dirId>/` where dirId = model id or "taesd". */
fun ImageModelFile.toDownload(dirId: String) = DownloadableFile(url = url, fileName = fileName, sizeBytes = sizeBytes, subdir = dirId, sha256 = sha256)

/** Download order: text first, then the projector (Downloading.fileIndex 1/2, 2/2). */
val CatalogModel.downloads: List<DownloadableFile> get() = files.map { it.toDownload(id) }

/** The checkpoint first, then the shared TAESD decoder (skipped by the queue when it is already complete). */
val ImageCatalogModel.downloads: List<DownloadableFile>
    get() = listOf(file.toDownload(id), ImageModelCatalog.taesd.toDownload(ImageModelCatalog.TAESD_DIR_ID))

/**
 * Storage layout under `filesDir/models` (spec Section 2). Everything lives on internal storage: external
 * storage is FUSE-backed and would slow the mmap'd weights.
 *
 *   models/<modelId>/<fileName>              complete catalog / image-model file
 *   models/<modelId>/<fileName>.part(.json)  in-progress or paused download
 *   models/taesd/<fileName>                  TAESD decoder shared by both image models
 *   models/imported/<import-id>/{model|mmproj}.gguf + meta.json
 *
 * The primary constructor takes a plain directory so JVM unit tests can use a temp folder; the app uses the
 * Context overload. `diskStats` returns (available, total) bytes for the root's filesystem (StatFs on Android);
 * `catalog` is the row set `scan()` recognises (tests substitute a small one).
 */
class ModelFiles(
    val root: File,
    private val resolver: ContentResolver? = null,
    private val diskStats: (File) -> Pair<Long, Long> = { dir -> StatFs(dir.path).let { it.availableBytes to it.totalBytes } },
    private val catalog: List<CatalogModel> = ModelCatalog.models,
) {
    constructor(context: Context) : this(File(context.filesDir, "models"), context.contentResolver)

    val importedRoot: File get() = File(root, IMPORTED_DIR)

    fun dirFor(modelId: String): File =
        if (modelId.startsWith(IMPORT_PREFIX)) File(importedRoot, modelId) else File(root, modelId)

    fun fileFor(d: DownloadableFile): File = File(File(root, d.subdir), d.fileName)
    fun textFile(m: CatalogModel): File = fileFor(m.text.toDownload(m.id))
    fun mmprojFile(m: CatalogModel): File? = m.mmproj?.let { fileFor(it.toDownload(m.id)) }
    fun partFile(f: File): File = File(f.path + PART_SUFFIX)
    fun partJsonFile(f: File): File = File(f.path + PART_JSON_SUFFIX)

    /** A file counts as complete only at the exact catalog size: a truncated copy is worse than no copy (mmap would SIGBUS). */
    fun isComplete(d: DownloadableFile): Boolean = fileFor(d).let { it.isFile && it.length() == d.sizeBytes }
    fun isDownloaded(files: List<DownloadableFile>): Boolean = files.all { isComplete(it) }
    /** Bytes still to transfer: complete files and `.part` bytes are subtracted (free-space gate, metered prompt). */
    fun remainingBytes(files: List<DownloadableFile>): Long = files.sumOf { d ->
        if (isComplete(d)) 0L else (d.sizeBytes - partFile(fileFor(d)).length()).coerceAtLeast(0L)
    }
    /** Sum of `.part` bytes of [files] (shared files included). */
    fun partialBytes(files: List<DownloadableFile>): Long = files.sumOf { partFile(fileFor(it)).length() }
    fun totalBytes(files: List<DownloadableFile>): Long = files.sumOf { it.sizeBytes }

    data class Scan(val models: List<LocalModel>, val partialBytes: Map<String, Long>)

    /** Complete catalog dirs + imported/<id>/meta.json entries, plus partial bytes per directory id (sum of *.part sizes). */
    suspend fun scan(): Scan = withContext(Dispatchers.IO) {
        val models = ArrayList<LocalModel>()
        for (m in catalog) {
            if (!isDownloaded(m.downloads)) continue
            val text = textFile(m)
            val mmproj = mmprojFile(m)
            models += LocalModel(
                id = m.id, displayName = m.displayName, textPath = text.path, mmprojPath = mmproj?.path,
                textBytes = text.length(), mmprojBytes = mmproj?.length() ?: 0L, quant = m.text.quant,
                catalog = m, importedAt = 0L,
            )
        }
        importedRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            readMeta(dir)?.let { meta ->
                val text = File(dir, meta.textFileName)
                if (!text.isFile) return@forEach
                val mmproj = meta.mmprojFileName?.let { File(dir, it) }?.takeIf { it.isFile }
                models += LocalModel(
                    id = meta.id, displayName = meta.displayName, textPath = text.path, mmprojPath = mmproj?.path,
                    textBytes = text.length(), mmprojBytes = mmproj?.length() ?: 0L,
                    quant = runCatching { Quant.valueOf(meta.quant) }.getOrDefault(Quant.OTHER),
                    catalog = null, importedAt = meta.importedAt,
                )
            }
        }
        val partial = HashMap<String, Long>()
        root.listFiles()?.filter { it.isDirectory && it.name != IMPORTED_DIR }?.forEach { dir ->
            val bytes = dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.sumOf { it.length() } ?: 0L
            if (bytes > 0) partial[dir.name] = bytes
        }
        Scan(models, partial)
    }

    /**
     * Copies via ContentResolver into imported/<import-id>/{model|mmproj}.gguf, verifies the GGUF magic, computes
     * sha8 (Section 2), reads general.architecture / general.file_type / general.name from the header for the quant
     * and display-name defaults, writes meta.json. isMmproj=true requires pairWith = an existing import id.
     */
    suspend fun importGguf(uri: Uri, displayName: String?, isMmproj: Boolean, pairWith: String?): LocalModel {
        val resolver = resolver ?: throw IOException("No content resolver")
        val name = uri.lastPathSegment
        return importFrom({ resolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file") }, name, displayName, isMmproj, pairWith)
    }

    /** Same as [importGguf] but from any stream (unit tests, future file-path imports). [sourceName] seeds the display name. */
    suspend fun importFrom(open: () -> InputStream, sourceName: String?, displayName: String?, isMmproj: Boolean, pairWith: String?): LocalModel =
        withContext(Dispatchers.IO) {
            importedRoot.mkdirs()
            if (isMmproj) importProjector(open, pairWith) else importModel(open, sourceName, displayName)
        }

    private suspend fun importProjector(open: () -> InputStream, pairWith: String?): LocalModel {
        val id = pairWith ?: throw IOException("Choose the model this projector belongs to")
        val dir = File(importedRoot, id)
        val meta = readMeta(dir) ?: throw IOException("Unknown import id $id")
        val target = File(dir, MMPROJ_FILE)
        val tmp = File(dir, "$MMPROJ_FILE.tmp")
        try {
            copyTo(open, tmp)
            if (!isGguf(tmp)) throw IOException("Not a GGUF file")
            val header = GgufHeader.read(tmp)
            // Projectors carry general.architecture = "clip"; a text model imported as a projector would crash mtmd.
            if (header.architecture != null && header.architecture != "clip") throw IOException("Not a projector file (architecture ${header.architecture})")
            if (!tmp.renameTo(target)) throw IOException("Could not move projector into place")
        } finally { tmp.delete() }
        writeMeta(dir, meta.copy(mmprojFileName = MMPROJ_FILE, mmprojBytes = target.length()))
        return scan().models.first { it.id == id }
    }

    private suspend fun importModel(open: () -> InputStream, sourceName: String?, displayName: String?): LocalModel {
        val staging = File(importedRoot, ".staging-${System.nanoTime()}")
        staging.mkdirs()
        val tmp = File(staging, MODEL_FILE)
        try {
            copyTo(open, tmp)
            if (!isGguf(tmp)) throw IOException("Not a GGUF file")
            val header = GgufHeader.read(tmp)
            if (header.architecture == "clip") throw IOException("This is a projector file; import the model first, then pair it")
            val id = IMPORT_PREFIX + sha8(tmp)
            val dir = File(importedRoot, id)
            // Same bytes imported twice => same id: replace the previous copy rather than keeping two.
            if (dir.exists()) dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IOException("Could not move model into place")
            val name = displayName?.takeIf { it.isNotBlank() } ?: header.name?.takeIf { it.isNotBlank() }
                ?: (sourceName ?: id).substringAfterLast('/').substringAfterLast(':').removeSuffix(".gguf")
            val meta = ImportMeta(
                id = id, displayName = name, textFileName = MODEL_FILE, mmprojFileName = null,
                quant = header.quant.name, textBytes = File(dir, MODEL_FILE).length(), mmprojBytes = 0L,
                importedAt = System.currentTimeMillis(), sha8 = id.removePrefix(IMPORT_PREFIX),
                architecture = header.architecture,
            )
            writeMeta(dir, meta)
            return scan().models.first { it.id == id }
        } finally { staging.deleteRecursively() }
    }

    /** Whole directory incl. .part/.part.json. The shared TAESD directory is never touched here. */
    suspend fun delete(modelId: String) = withContext(Dispatchers.IO) { dirFor(modelId).deleteRecursively(); Unit }

    /** .part + .part.json of every file in [files] (an image model's paused TAESD partial goes with it). */
    suspend fun discardPartial(files: List<DownloadableFile>) = withContext(Dispatchers.IO) {
        files.forEach { d -> val f = fileFor(d); partFile(f).delete(); partJsonFile(f).delete() }
    }

    /** .part + .part.json only, in the model's own directory (imports and unknown ids). */
    suspend fun discardPartial(modelId: String) = withContext(Dispatchers.IO) {
        dirFor(modelId).listFiles()?.filter { it.name.endsWith(PART_SUFFIX) || it.name.endsWith(PART_JSON_SUFFIX) }?.forEach { it.delete() }
        Unit
    }

    /** StatFs on the models root (same filesystem as filesDir); modelsBytes includes .part files. */
    fun storageInfo(): StorageInfo {
        root.mkdirs()
        var models = 0L; var partial = 0L
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            models += f.length()
            if (f.name.endsWith(PART_SUFFIX)) partial += f.length()
        }
        val (free, total) = diskStats(root)
        return StorageInfo(modelsBytes = models, partialBytes = partial, freeBytes = free, totalBytes = total)
    }

    fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { s -> val b = ByteArray(4); s.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    }.getOrDefault(false)

    // ---- imports ------------------------------------------------------------------------------------------------

    @Serializable
    data class ImportMeta(
        val id: String, val displayName: String, val textFileName: String, val mmprojFileName: String? = null,
        val quant: String, val textBytes: Long, val mmprojBytes: Long = 0L, val importedAt: Long, val sha8: String,
        val architecture: String? = null,
    )

    private fun readMeta(dir: File): ImportMeta? = runCatching {
        json.decodeFromString<ImportMeta>(File(dir, META_FILE).readText())
    }.getOrNull()

    private fun writeMeta(dir: File, meta: ImportMeta) {
        val tmp = File(dir, "$META_FILE.tmp")
        tmp.writeText(json.encodeToString(meta))
        if (!tmp.renameTo(File(dir, META_FILE))) throw IOException("Could not write meta.json")
    }

    private fun copyTo(open: () -> InputStream, dest: File) {
        open().use { src -> dest.outputStream().use { dst -> src.copyTo(dst, COPY_BUFFER); dst.fd.sync() } }
    }

    companion object {
        const val IMPORTED_DIR = "imported"
        const val IMPORT_PREFIX = "import-"
        const val MODEL_FILE = "model.gguf"
        const val MMPROJ_FILE = "mmproj.gguf"
        const val META_FILE = "meta.json"
        const val PART_SUFFIX = ".part"
        const val PART_JSON_SUFFIX = ".part.json"
        private const val COPY_BUFFER = 1 shl 20
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** First 8 hex chars of sha256(first 64 MiB of the file ‖ file size as 8 LE bytes): fast on multi-GB files, stable. */
        fun sha8(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(COPY_BUFFER)
                var remaining = 64L shl 20
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    md.update(buf, 0, n); remaining -= n
                }
            }
            val size = file.length()
            md.update(ByteArray(8) { i -> (size ushr (8 * i)).toByte() })
            return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 8)
        }
    }
}

/**
 * The three `general.*` keys an import needs, read from the GGUF v2/v3 key-value header without touching the
 * tensors. Values of other keys are skipped; token arrays (250k strings) cost a few MB of reads at most.
 */
data class GgufHeader(val architecture: String?, val name: String?, val fileType: Int?) {
    /** llama_ftype -> Quant (llama.h: 0 F32, 1 F16, 2 Q4_0, 7 Q8_0, 15 Q4_K_M, 25 IQ4_NL). */
    val quant: Quant get() = when (fileType) {
        1 -> Quant.F16; 2 -> Quant.Q4_0; 7 -> Quant.Q8_0; 15 -> Quant.Q4_K_M; 25 -> Quant.IQ4_NL; else -> Quant.OTHER
    }

    companion object {
        private const val T_UINT8 = 0; private const val T_INT8 = 1; private const val T_UINT16 = 2; private const val T_INT16 = 3
        private const val T_UINT32 = 4; private const val T_INT32 = 5; private const val T_FLOAT32 = 6; private const val T_BOOL = 7
        private const val T_STRING = 8; private const val T_ARRAY = 9; private const val T_UINT64 = 10; private const val T_INT64 = 11
        private const val T_FLOAT64 = 12
        private const val MAX_STRING = 1 shl 20

        fun read(file: File): GgufHeader = RandomAccessFile(file, "r").use { raf -> read(raf) }

        private fun read(raf: RandomAccessFile): GgufHeader {
            val r = LeReader(raf)
            if (r.u32() != 0x46554747L) throw IOException("Not a GGUF file")   // "GGUF" little-endian
            val version = r.u32()
            if (version < 2 || version > 3) throw IOException("Unsupported GGUF version $version")
            r.u64()                                   // tensor count
            val kvCount = r.u64()
            var arch: String? = null; var name: String? = null; var ftype: Int? = null
            var i = 0L
            while (i < kvCount && (arch == null || name == null || ftype == null)) {
                val key = r.string()
                val type = r.u32().toInt()
                when (key) {
                    "general.architecture" -> if (type == T_STRING) arch = r.string() else r.skipValue(type)
                    "general.name" -> if (type == T_STRING) name = r.string() else r.skipValue(type)
                    "general.file_type" -> if (type == T_UINT32 || type == T_INT32) ftype = r.u32().toInt() else r.skipValue(type)
                    else -> r.skipValue(type)
                }
                i++
            }
            return GgufHeader(arch, name, ftype)
        }

        private class LeReader(private val raf: RandomAccessFile) {
            private val buf = ByteArray(8)
            private fun fill(n: Int) { raf.readFully(buf, 0, n) }
            fun u32(): Long { fill(4); return (buf[0].toLong() and 0xFF) or ((buf[1].toLong() and 0xFF) shl 8) or ((buf[2].toLong() and 0xFF) shl 16) or ((buf[3].toLong() and 0xFF) shl 24) }
            fun u64(): Long { fill(8); var v = 0L; for (k in 7 downTo 0) v = (v shl 8) or (buf[k].toLong() and 0xFF); return v }
            fun string(): String {
                val len = u64()
                if (len < 0 || len > MAX_STRING) throw IOException("Corrupt GGUF string length")
                val b = ByteArray(len.toInt()); raf.readFully(b); return String(b, Charsets.UTF_8)
            }
            fun skip(n: Long) { raf.seek(raf.filePointer + n) }
            fun skipValue(type: Int) {
                when (type) {
                    T_UINT8, T_INT8, T_BOOL -> skip(1)
                    T_UINT16, T_INT16 -> skip(2)
                    T_UINT32, T_INT32, T_FLOAT32 -> skip(4)
                    T_UINT64, T_INT64, T_FLOAT64 -> skip(8)
                    T_STRING -> skip(u64().also { if (it < 0) throw IOException("Corrupt GGUF") })
                    T_ARRAY -> {
                        val elem = u32().toInt(); val count = u64()
                        val fixed = when (elem) {
                            T_UINT8, T_INT8, T_BOOL -> 1L; T_UINT16, T_INT16 -> 2L; T_UINT32, T_INT32, T_FLOAT32 -> 4L
                            T_UINT64, T_INT64, T_FLOAT64 -> 8L; else -> 0L
                        }
                        if (fixed > 0) skip(fixed * count) else { var k = 0L; while (k < count) { skipValue(elem); k++ } }
                    }
                    else -> throw IOException("Unknown GGUF value type $type")
                }
            }
        }
    }
}
