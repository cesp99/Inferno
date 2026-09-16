package to.eyed.inferno.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.PromptImage
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ImageException(message: String) : Exception(message)

/** Pure geometry / hashing rules shared by import, prompt extraction and the unit tests (no Android types). */
object ImageMath {
    const val THUMB_EDGE = 256
    const val JPEG_QUALITY = 85
    const val THUMB_QUALITY = 80

    val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    data class Size(val width: Int, val height: Int)

    fun isSupportedMime(mime: String?): Boolean = mime != null && mime.lowercase() in SUPPORTED_MIME

    /** Sample size for the ImageDecoder header listener: the decoder never allocates the full-size bitmap. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int = max(1, max(width, height) / max(1, maxEdge))

    /** Largest size with the long edge <= [maxEdge], aspect preserved, never upscaled, never below 1 px. */
    fun fitEdge(width: Int, height: Int, maxEdge: Int): Size {
        val long = max(width, height)
        if (long <= maxEdge || long <= 0) return Size(max(1, width), max(1, height))
        val scale = maxEdge.toDouble() / long
        return if (width >= height) Size(maxEdge, max(1, (height * scale).roundToInt()))
        else Size(max(1, (width * scale).roundToInt()), maxEdge)
    }

    /**
     * Long edge handed to the vision encoder: min(detail.maxEdgePx, catalog cap) = FAST 336, BALANCED 448, HIGH the
     * catalog `maxImageEdgePx`. Same rule as engine ContextManager.imageEdgeCap(model, detail), which is
     * the entry point callers with a LocalModel should use (see [ImageUtil.toPromptImage]).
     */
    fun promptEdge(detail: ImageDetail, catalogMaxEdgePx: Int = Int.MAX_VALUE): Int =
        minOf(detail.maxEdgePx, catalogMaxEdgePx).coerceAtLeast(1)

    fun promptSize(width: Int, height: Int, detail: ImageDetail, catalogMaxEdgePx: Int = Int.MAX_VALUE): Size =
        fitEdge(width, height, promptEdge(detail, catalogMaxEdgePx))

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in d) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0xf]) }
        return sb.toString()
    }
    private val HEX = "0123456789abcdef".toCharArray()

    /** Packs ARGB pixels as RGB888 (mtmd input). */
    fun toRgb888(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        var o = 0
        for (c in pixels) {
            out[o++] = (c shr 16).toByte(); out[o++] = (c shr 8).toByte(); out[o++] = c.toByte()
        }
        return out
    }

    /** "<sha>.jpg" / "<sha>.thumb.jpg" -> "<sha>"; anything else -> null. */
    fun idFromFileName(name: String): String? {
        val stem = name.substringBefore('.')
        return if (stem.length == 64 && (name == "$stem.jpg" || name == "$stem.thumb.jpg")) stem else null
    }
}

/** Where a generated PNG landed. */
data class GeneratedFiles(val id: String, val path: String, val thumbPath: String)

class ImageUtil(private val context: Context) {
    val imagesDir: File = File(context.filesDir, "images")
    /** Generated images; never touched by the orphan sweep (rows in generated_images own them). */
    val generatedDir: File = File(imagesDir, "gen")
    private val cameraDir: File get() = File(context.cacheDir, "camera")

    fun pathOf(id: String) = File(imagesDir, "$id.jpg")
    fun thumbOf(id: String) = File(imagesDir, "$id.thumb.jpg")
    fun attachment(id: String, width: Int, height: Int) = Attachment(id, pathOf(id).path, thumbOf(id).path, width, height)

    /**
     * Decode (EXIF-rotated by ImageDecoder, software allocator), downscale the long edge to [maxEdge], store as
     * JPEG q85 under files/images/<sha256>.jpg plus a 256 px thumb. The id is the sha256 of the stored JPEG bytes,
     * so re-importing the same file yields the same id (prefix-cache hit in the engine, no duplicate file).
     */
    suspend fun importImage(uri: Uri, maxEdge: Int = 1536): Attachment = withContext(Dispatchers.IO) {
        val declared = if (uri.scheme == "file") null else context.contentResolver.getType(uri)
        if (declared != null && !ImageMath.isSupportedMime(declared)) throw ImageException(UNSUPPORTED)
        val source = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
        else ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = decodeSampled(source, maxEdge)
        val fit = ImageMath.fitEdge(decoded.width, decoded.height, maxEdge)
        val bitmap = scaleTo(decoded, fit)
        try {
            val jpeg = ByteArrayOutputStream(bitmap.byteCount / 8).also { bitmap.compress(Bitmap.CompressFormat.JPEG, ImageMath.JPEG_QUALITY, it) }.toByteArray()
            val id = ImageMath.sha256Hex(jpeg)
            imagesDir.mkdirs()
            val main = pathOf(id)
            if (!main.isFile) writeAtomic(main, jpeg)
            val thumb = thumbOf(id)
            if (!thumb.isFile) writeThumb(bitmap, thumb)
            // Touch both so a re-imported (still unsent) attachment is not swept as an old orphan.
            val now = System.currentTimeMillis(); main.setLastModified(now); thumb.setLastModified(now)
            attachment(id, bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }

    /** Output target for ACTION_IMAGE_CAPTURE; cache/camera is the `camera` root in res/xml/file_paths.xml. */
    fun newCameraUri(): Uri {
        cameraDir.mkdirs()
        val file = File(cameraDir, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * RGB888 bytes for mtmd. The long edge is clamped to ImageMath.promptEdge(detail, catalogMaxEdgePx) and the
     * output size equals [placeholder]'s exactly (the token count computed on the placeholder must match the encode).
     */
    suspend fun toPromptImage(att: Attachment, detail: ImageDetail, catalogMaxEdgePx: Int = Int.MAX_VALUE): PromptImage =
        withContext(Dispatchers.IO) {
            val target = ImageMath.promptSize(att.width, att.height, detail, catalogMaxEdgePx)
            val file = File(att.path)
            if (!file.isFile) throw ImageException("Attachment file is missing")
            val decoded = decodeSampled(ImageDecoder.createSource(file), max(target.width, target.height))
            val bitmap = scaleTo(decoded, target)
            try {
                val px = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PromptImage(att.id, bitmap.width, bitmap.height, ImageMath.toRgb888(px))
            } finally { bitmap.recycle() }
        }

    /** Model-aware overload: the cap comes from ContextManager.imageEdgeCap (catalog `maxImageEdgePx`, imports 1024). */
    suspend fun toPromptImage(att: Attachment, model: LocalModel, detail: ImageDetail): PromptImage =
        toPromptImage(att, detail, ContextManager.imageEdgeCap(model, detail))

    /** Width/height only (token counting); rgb = null. */
    fun placeholder(att: Attachment, detail: ImageDetail, catalogMaxEdgePx: Int = Int.MAX_VALUE): PromptImage {
        val s = ImageMath.promptSize(att.width, att.height, detail, catalogMaxEdgePx)
        return PromptImage(att.id, s.width, s.height, null)
    }

    fun placeholder(att: Attachment, model: LocalModel, detail: ImageDetail): PromptImage =
        placeholder(att, detail, ContextManager.imageEdgeCap(model, detail))

    suspend fun deleteIfUnreferenced(imageId: String, refs: Int) = withContext(Dispatchers.IO) {
        if (refs <= 0) { pathOf(imageId).delete(); thumbOf(imageId).delete() }
    }

    /**
     * Startup sweep: delete every attachment file with no message_images row whose newest file is older than
     * [olderThanMs] (attachments picked but never sent, process death before send). Recent files are kept because
     * they may be sitting in the composer right now. gen/ is skipped: generated images are owned by their DB row.
     */
    suspend fun sweepOrphans(refsOf: suspend (String) -> Int, olderThanMs: Long = 24 * 3600_000L) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val files = imagesDir.listFiles { f -> f.isFile } ?: return@withContext
        val ids = LinkedHashSet<String>()
        for (f in files) {
            val id = ImageMath.idFromFileName(f.name)
            if (id == null) { if (f.lastModified() < cutoff) f.delete() } else ids += id   // stray temp files
        }
        for (id in ids) {
            val newest = max(pathOf(id).lastModified(), thumbOf(id).lastModified())
            if (newest >= cutoff) continue
            if (refsOf(id) == 0) { pathOf(id).delete(); thumbOf(id).delete() }
        }
    }

    /** cache/camera: system-camera captures already imported into files/images. */
    suspend fun clearCache() = withContext(Dispatchers.IO) { cameraDir.listFiles()?.forEach { it.delete() }; Unit }

    // ---- generated images ----

    /** Stores an ImageGenEvent.Done PNG as files/images/gen/<id>.png plus a 256 px JPEG thumb. */
    suspend fun saveGenerated(png: ByteArray, id: String = UUID.randomUUID().toString()): GeneratedFiles = withContext(Dispatchers.IO) {
        generatedDir.mkdirs()
        val file = File(generatedDir, "$id.png")
        writeAtomic(file, png)
        val thumb = File(generatedDir, "$id.thumb.jpg")
        val bmp = decodeSampled(ImageDecoder.createSource(file), ImageMath.THUMB_EDGE)
        try { writeThumb(bmp, thumb) } finally { bmp.recycle() }
        GeneratedFiles(id, file.path, thumb.path)
    }

    suspend fun deleteGenerated(files: GeneratedFiles) = deleteGenerated(files.path, files.thumbPath)
    suspend fun deleteGenerated(path: String, thumbPath: String) = withContext(Dispatchers.IO) { File(path).delete(); File(thumbPath).delete(); Unit }

    /**
     * "Save to Photos": copies a stored PNG/JPEG into MediaStore under Pictures/Inferno (scoped storage, no permission
     * needed on API 29+). Returns the new MediaStore item Uri.
     */
    suspend fun exportToPictures(path: String, displayName: String? = null): Uri = withContext(Dispatchers.IO) {
        val src = File(path)
        if (!src.isFile) throw ImageException("Image file is missing")
        val isPng = src.name.endsWith(".png", ignoreCase = true)
        val name = (displayName ?: "inferno-${System.currentTimeMillis()}") + if (isPng) ".png" else ".jpg"
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (isPng) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values) ?: throw ImageException("Couldn't save to Photos")
        try {
            resolver.openOutputStream(item)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw ImageException("Couldn't save to Photos")
            resolver.update(item, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            item
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw if (e is ImageException) e else ImageException("Couldn't save to Photos")
        }
    }

    // ---- internals ----

    /**
     * The sample size is applied in the header listener so a 108 MP photo never materialises at full size
     * (~430 MB); decodeBitmap (not decodeDrawable) flattens animated GIF/WebP to the first frame. EXIF orientation
     * is applied by ImageDecoder itself. Any decoder failure surfaces as ImageException.
     */
    private fun decodeSampled(source: ImageDecoder.Source, maxEdge: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (!ImageMath.isSupportedMime(info.mimeType)) throw ImageException(UNSUPPORTED)
            decoder.setTargetSampleSize(ImageMath.sampleSize(info.size.width, info.size.height, maxEdge))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } catch (e: ImageException) {
        throw e
    } catch (e: Exception) {
        throw ImageException(UNREADABLE)
    }

    /** Scales to exactly [size] (the decoder may round the sample size); recycles the input when a copy was made. */
    private fun scaleTo(bitmap: Bitmap, size: ImageMath.Size): Bitmap {
        if (bitmap.width == size.width && bitmap.height == size.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun writeThumb(bitmap: Bitmap, target: File) {
        val s = ImageMath.fitEdge(bitmap.width, bitmap.height, ImageMath.THUMB_EDGE)
        val thumb = if (s.width == bitmap.width && s.height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, s.width, s.height, true)
        try {
            val bytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, ImageMath.THUMB_QUALITY, it) }.toByteArray()
            writeAtomic(target, bytes)
        } finally { if (thumb !== bitmap) thumb.recycle() }
    }

    /** Temp file + rename so a crash mid-write never leaves a truncated <sha>.jpg that would be trusted by its name. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw ImageException("Couldn't store the image") }
        } finally { tmp.delete() }
    }

    companion object {
        const val UNREADABLE = "Couldn't read that image"
        const val UNSUPPORTED = "That image type isn't supported"
        const val EXPORT_RELATIVE_PATH = "Pictures/Inferno"
    }
}
