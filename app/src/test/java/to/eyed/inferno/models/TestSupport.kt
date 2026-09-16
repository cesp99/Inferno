package to.eyed.inferno.models

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/** Builds a minimal but structurally valid GGUF v3 header (no tensors) followed by filler bytes up to [totalSize]. */
object Gguf {
    private const val T_UINT32 = 4; private const val T_STRING = 8; private const val T_ARRAY = 9; private const val T_FLOAT32 = 6

    fun bytes(arch: String? = "llama", name: String? = "Test Model", fileType: Int? = 2, totalSize: Int = 4096, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        fun f32(v: Float) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        u32(3)
        u64(0)                                   // tensor count
        val kvs = ArrayList<() -> Unit>()
        // Two arrays first so the reader must skip them correctly before reaching general.*.
        kvs += { str("tokenizer.ggml.tokens"); u32(T_ARRAY); u32(T_STRING); u64(3); str("a"); str("bb"); str("ccc") }
        kvs += { str("tokenizer.ggml.scores"); u32(T_ARRAY); u32(T_FLOAT32); u64(3); f32(1f); f32(2f); f32(3f) }
        if (arch != null) kvs += { str("general.architecture"); u32(T_STRING); str(arch) }
        if (name != null) kvs += { str("general.name"); u32(T_STRING); str(name) }
        if (fileType != null) kvs += { str("general.file_type"); u32(T_UINT32); u32(fileType) }
        u64(kvs.size.toLong())
        kvs.forEach { it() }
        val header = out.toByteArray()
        require(totalSize >= header.size) { "totalSize too small" }
        val filler = Random(seed).nextBytes(totalSize - header.size)
        return header + filler
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun tempDir(prefix: String): File = File.createTempFile("inferno-$prefix", "").apply { delete(); mkdirs() }

/** ModelFiles over a temp dir with fake disk stats (free/total) so StatFs is never touched on the JVM. */
fun testFiles(root: File, free: Long = 100L shl 30, total: Long = 128L shl 30) = ModelFiles(root, null, { free to total })

/** Creates a sparse file of exactly [size] bytes starting with the GGUF magic. */
fun File.makeSparseGguf(size: Long) {
    parentFile?.mkdirs()
    RandomAccessFile(this, "rw").use { it.setLength(size); it.seek(0); it.write("GGUF".toByteArray()) }
}
