package to.eyed.inferno.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Minimal GGUF v2/v3 key-value reader for the few hyper-parameters the planner needs when a model has no catalog
 * row (SAF imports): training context, layer count and KV geometry. Walks the KV section once, skipping arrays
 * (the 150k-entry tokenizer arrays cost a few ms of sequential reads) and never touches tensor data.
 */
data class GgufFacts(
    val arch: String,
    val contextLength: Int?,
    val nLayer: Int?,
    val nHead: Int?,
    val nHeadKv: Int?,
    val nEmbd: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
) {
    /**
     * f16 KV bytes per token = 2 (K and V) x layers x kv heads x head dim x 2 bytes. Over-estimates hybrid
     * (GDN/conv) models whose recurrent layers hold no KV; acceptable for the AUTO kv-type decision on imports.
     */
    val kvBytesPerTokenF16: Long? get() {
        val layers = nLayer ?: return null
        val heads = nHeadKv ?: nHead ?: return null
        val headDim = keyLength ?: (nEmbd?.let { e -> nHead?.let { h -> if (h > 0) e / h else null } }) ?: return null
        val vDim = valueLength ?: headDim
        return 2L * layers * heads * (headDim + vDim)
    }
}

object GgufHeader {
    private const val MAGIC = 0x46554747 // "GGUF" little-endian

    fun read(path: String): GgufFacts? = try {
        DataInputStream(BufferedInputStream(FileInputStream(File(path)), 1 shl 16)).use { parse(it) }
    } catch (e: Exception) {
        EngineLog.w("Gguf", "cannot read header of $path: ${e.message}")
        null
    }

    private fun parse(inp: DataInputStream): GgufFacts? {
        if (u32(inp) != MAGIC) return null
        val version = u32(inp)
        if (version < 2 || version > 3) return null
        u64(inp)                    // n_tensors
        val nKv = u64(inp)
        val values = HashMap<String, Any>()
        for (i in 0 until nKv) {
            val key = string(inp)
            val type = u32(inp)
            val v = value(inp, type, key)
            if (v != null) values[key] = v
        }
        val arch = values["general.architecture"] as? String ?: return null
        fun int(k: String) = (values["$arch.$k"] as? Number)?.toInt()
        return GgufFacts(
            arch = arch, contextLength = int("context_length"), nLayer = int("block_count"),
            nHead = int("attention.head_count"), nHeadKv = int("attention.head_count_kv"), nEmbd = int("embedding_length"),
            keyLength = int("attention.key_length"), valueLength = int("attention.value_length"),
        )
    }

    /** Scalars are returned; arrays are consumed and dropped (null), except that a scalar-valued array head is not needed. */
    private fun value(inp: DataInputStream, type: Int, key: String): Any? = when (type) {
        0 -> inp.readUnsignedByte()
        1 -> inp.readByte().toInt()
        2 -> u16(inp)
        3 -> u16(inp).toShort().toInt()
        4 -> u32(inp).toLong() and 0xFFFF_FFFFL
        5 -> u32(inp)
        6 -> java.lang.Float.intBitsToFloat(u32(inp))
        7 -> inp.readUnsignedByte() != 0
        8 -> string(inp)
        9 -> { skipArray(inp, key); null }
        10 -> u64(inp)
        11 -> u64(inp)
        12 -> java.lang.Double.longBitsToDouble(u64(inp))
        else -> throw IllegalStateException("unknown gguf type $type for $key")
    }

    private fun skipArray(inp: DataInputStream, key: String) {
        val elemType = u32(inp)
        val n = u64(inp)
        val fixed = when (elemType) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; 8 -> -1L; 9 -> -2L
            else -> throw IllegalStateException("unknown gguf array type $elemType for $key") }
        when {
            fixed > 0 -> skipFully(inp, n * fixed)
            fixed == -1L -> for (i in 0 until n) skipFully(inp, u64(inp))
            else -> for (i in 0 until n) skipArray(inp, key)
        }
    }

    private fun skipFully(inp: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = inp.skip(left)
            if (s <= 0) { if (inp.read() < 0) throw EOFException(); left -= 1 } else left -= s
        }
    }

    private fun string(inp: DataInputStream): String {
        val len = u64(inp)
        if (len > (1L shl 24)) throw IllegalStateException("gguf string too long: $len")
        val bytes = ByteArray(len.toInt())
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)   // String(...) replaces malformed input; LlamaNative.utf8 would load the .so
    }

    private fun u16(inp: DataInputStream): Int = inp.readUnsignedByte() or (inp.readUnsignedByte() shl 8)
    private fun u32(inp: DataInputStream): Int = u16(inp) or (u16(inp) shl 16)
    private fun u64(inp: DataInputStream): Long = (u32(inp).toLong() and 0xFFFF_FFFFL) or ((u32(inp).toLong() and 0xFFFF_FFFFL) shl 32)
}
