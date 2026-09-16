package to.eyed.inferno.engine

/**
 * Called on the engine thread. Return false to abort the operation.
 * done/total are always non-negative and monotonic within one operation. Units per phase:
 *  LOAD  -> permille of the weight load (0..1000);
 *  TEXT / IMAGE -> prompt tokens evaluated so far / prompt tokens to evaluate (an image chunk counts as its
 *                  n_tokens, so the bar is monotonic across text and image chunks; IMAGE is reported for the
 *                  callback(s) issued while an image chunk is being encoded + decoded);
 *  BENCH -> reps done / reps.
 */
fun interface ProgressCallback { fun onProgress(done: Int, total: Int, phase: Int): Boolean }
object Phase { const val LOAD = 0; const val TEXT = 1; const val IMAGE = 2; const val BENCH = 3 }

/** Read by JNI via GetFieldID; keep rule in proguard. content is raw UTF-8 (never modified UTF-8). */
class NativeChatMessage(
    @JvmField val role: String,          // "system" | "user" | "assistant"
    @JvmField val content: ByteArray,    // UTF-8
    @JvmField val imageIds: Array<String>,
)

/** rgb == null => placeholder bitmap (token counting only). rgb is packed RGB888, width*height*3 bytes. */
class NativeImage(
    @JvmField val id: String,            // sha256 hex of the RGB bytes (stable across turns)
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val rgb: ByteArray?,
)

/** Index constants for the IntArray passed to LlamaNative.contextCreate / estimateMemory. */
object CtxP {
    const val N_CTX = 0; const val N_BATCH = 1; const val N_UBATCH = 2; const val N_THREADS = 3
    const val N_THREADS_BATCH = 4; const val BIG_CORES_ONLY = 5; const val FLASH_ATTN = 6   // 0 off (LLAMA_FLASH_ATTN_TYPE_DISABLED), 1 on (ENABLED); Kotlin never sends -1 (AUTO resolves to enabled on CPU)
    const val TYPE_K = 7; const val TYPE_V = 8           // ggml_type: 0 F32, 1 F16, 2 Q4_0, 8 Q8_0
    const val SWA_FULL = 9; const val POLL = 10; const val N_OUTPUTS_MAX = 11; const val SIZE = 12
}

/** Index constants for the FloatArray/IntArray pair passed to LlamaNative.samplerSet. */
object SmpF { const val TEMP = 0; const val TOP_P = 1; const val MIN_P = 2; const val TYPICAL_P = 3
              const val REPEAT_PENALTY = 4; const val FREQ_PENALTY = 5; const val PRESENCE_PENALTY = 6
              const val DRY_MULTIPLIER = 7; const val DRY_BASE = 8; const val SIZE = 9 }
object SmpI { const val TOP_K = 0; const val REPEAT_LAST_N = 1; const val DRY_ALLOWED_LENGTH = 2
              const val DRY_PENALTY_LAST_N = 3; const val SEED = 4; const val SIZE = 5 }

/** Layout of LlamaNative.modelInfoNumbers(). */
object MInfo { const val N_PARAMS = 0; const val SIZE_BYTES = 1; const val N_CTX_TRAIN = 2; const val N_LAYER = 3
               const val N_EMBD = 4; const val N_HEAD_KV = 5; const val N_SWA = 6; const val HAS_VISION = 7
               const val TEMPLATE_SUPPORTED = 8; const val N_VOCAB = 9; const val SIZE = 10 }
/** Layout of LlamaNative.modelInfoStrings(). */
object MInfoS { const val ARCH = 0; const val DESC = 1; const val NAME = 2; const val TEMPLATE_NAME = 3; const val SIZE = 4 }

/** Layout of LlamaNative.generateStats(). */
object GStat { const val PROMPT_TOKENS = 0; const val REUSED_TOKENS = 1; const val GEN_TOKENS = 2; const val PREFILL_MS = 3
               const val DECODE_MS = 4; const val IMAGE_ENCODE_MS = 5; const val KV_USED_TOKENS = 6; const val N_PAST = 7; const val SIZE = 8 }

/**
 * Layout of LlamaNative.estimateMemory(). All values in bytes.
 * MODEL = MODEL_RESIDENT + MODEL_MAPPED (total weight bytes, == llama_model_size() within 10 %).
 * MODEL_RESIDENT = weights that end up in anonymous RAM (repacked into CPU_KLEIDIAI / CPU_REPACK extra bufts);
 * MODEL_MAPPED = weights that stay file-backed on the plain CPU buffer (tensor_buft_overrides) and are evictable.
 * CLIP_COMPUTE = encoder compute buffer for the largest allowed image (from mtmd_get_memory_usage, summed over all bufts).
 */
object MemEst { const val MODEL = 0; const val KV = 1; const val COMPUTE = 2; const val MMPROJ = 3; const val DEVICE_TOTAL = 4
                const val MODEL_RESIDENT = 5; const val MODEL_MAPPED = 6; const val CLIP_COMPUTE = 7; const val SIZE = 8 }

/** Layout of LlamaNative.cpuTopology(). */
object Cpu { const val N_CORES = 0; const val N_BIG = 1; const val BIG_MASK = 2 /* bit i = core i is big */
             const val HAS_DOTPROD = 3; const val HAS_FP16 = 4; const val HAS_I8MM = 5; const val HAS_SVE = 6; const val SIZE = 7 }

/** Native finish reason codes (LlamaNative.generateFinishReason). */
object NFinish { const val RUNNING = 0; const val EOS = 1; const val LENGTH = 2; const val CONTEXT_FULL = 3; const val CANCELLED = 4; const val ERROR = 5 }
