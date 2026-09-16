package to.eyed.inferno.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Thin JNI bridge. Every function except [cancel], [lastError], [systemInfo], [cpuTopology] MUST be called on
 * the InferenceEngine thread. Handles are opaque pointers; 0 == failure. Errors never throw across JNI:
 * callers read [lastError] when a call returns 0 / negative / null-with-ERROR.
 * Free-form text (model metadata, error text) crosses JNI as raw UTF-8 ByteArrays, never as jstring: GGUF
 * `general.name`/`general.description` may contain 4-byte code points, which NewStringUTF (modified UTF-8) rejects
 * and CheckJNI aborts on. Kotlin decodes with [utf8] (CharsetDecoder, REPLACE).
 *
 * Template notes: Gemma 4 (`arch == "gemma4"`) is rendered by a hand-rolled formatter
 * (`templateName == "gemma4"`, `<|turn>role ... <turn|>`); put "<|think|>" at the very start of the system
 * message content to enable its thinking mode (same output as the official enable_thinking=true). Unknown
 * Jinja templates fall back to chatml (`templateSupported == 0`). [modelSetTemplate] forces a built-in name.
 */
object LlamaNative {
    init { System.loadLibrary("inferno") }

    // lifecycle
    @JvmStatic external fun backendInit(minLogPriority: Int, bigMask: Int)      // android.util.Log priority (DEBUG=3 .. ERROR=6); bigMask -> sched_setaffinity of the engine thread
    @JvmStatic external fun backendFree()
    @JvmStatic external fun systemInfo(): String                                // llama_print_system_info() (ASCII)
    @JvmStatic external fun setLogPriority(minLogPriority: Int)                 // live change of the backendInit floor; any thread
    @JvmStatic external fun modelBufferTypes(): String                          // "model buffer size" log lines of the last load (developer mode); any thread
    @JvmStatic external fun cpuTopology(): IntArray                             // see Cpu.*

    // model
    /** Loads the text weights; with mmprojPath != null also the projector (eager path, tests only). The normal path passes null and InferenceEngine loads the projector lazily via [mmprojLoad]. */
    @JvmStatic external fun modelLoad(path: String, mmprojPath: String?, useMmap: Boolean, nThreadsMmproj: Int,
                                      imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Long
    @JvmStatic external fun modelFree(model: Long)
    @JvmStatic external fun modelInfoNumbers(model: Long): LongArray            // see MInfo.*; HAS_VISION is 1 only while a vision projector is resident
    @JvmStatic external fun modelInfoStrings(model: Long): Array<ByteArray>     // see MInfoS.*; raw UTF-8
    @JvmStatic external fun modelMetaStr(model: Long, key: String): ByteArray?  // llama_model_meta_val_str, raw UTF-8
    /** null/"" = auto-detect, "gemma4" = hand-rolled Gemma 4, else a llama built-in template name (e.g. "chatml" to disable MiniCPM-V thinking). */
    @JvmStatic external fun modelSetTemplate(model: Long, name: String?)
    /** Wraps mtmd_init_from_file only (text weights untouched). imageMaxTokens = 0 => projector default. progress phase LOAD. */
    @JvmStatic external fun mmprojLoad(model: Long, mmprojPath: String, nThreads: Int, imageMinTokens: Int, imageMaxTokens: Int,
                                       progress: ProgressCallback?): Boolean
    @JvmStatic external fun mmprojFree(model: Long)                             // frees only the vision tower (memory pressure / text-only turn); idempotent
    @JvmStatic external fun mmprojLoaded(model: Long): Boolean

    // context
    @JvmStatic external fun contextCreate(model: Long, params: IntArray): Long   // params.size == CtxP.SIZE
    @JvmStatic external fun contextFree(ctx: Long)
    @JvmStatic external fun contextNCtx(ctx: Long): Int                          // actual, padded to 256
    /** Rebuilds both ggml threadpools (cpumask/poll cannot be changed in place, 4.2); cheap path only when just nGen shrinks. */
    @JvmStatic external fun contextSetThreads(ctx: Long, nGen: Int, nBatch: Int, bigCoresOnly: Boolean, poll: Int): Boolean
    @JvmStatic external fun contextWorkerTids(ctx: Long): IntArray               // engine thread tid + threadpool worker tids (ADPF session, 5.2)
    /** MemEst.*; no weights loaded. The no_alloc llama_model is cached per modelPath inside the Engine, so repeated calls cost one llama_init_from_model. */
    @JvmStatic external fun estimateMemory(modelPath: String, mmprojPath: String?, params: IntArray): LongArray   // empty array on error (lastError)
    @JvmStatic external fun estimateCacheClear()                                 // drops the cached no_alloc models (called from unload / delete)

    // sampling
    @JvmStatic external fun samplerSet(ctx: Long, f: FloatArray, i: IntArray, grammar: String?): Boolean

    // tokens / templates
    @JvmStatic external fun tokenCount(model: Long, textUtf8: ByteArray, addSpecial: Boolean, parseSpecial: Boolean): Int
    @JvmStatic external fun promptTokenCount(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>): Int // -1 error
    @JvmStatic external fun applyChatTemplate(model: Long, messages: Array<NativeChatMessage>, addAssistant: Boolean): ByteArray?

    // generation (pull-based)
    /** 0 ok · 1 cancelled · 2 prompt does not fit · -1 no context · -2 tokenize/template failed · -3 decode failed
     *  · -4 an image chunk has more tokens than n_batch (Kotlin planned the batch wrong; never let the helper split it)
     *  · -5 not enough free memory for the image encode (pre-encode check: availMemBytes < CLIP_COMPUTE + 400 MB;
     *       pass availMem minus the catalog encoderPeakBytes so the peak is part of the check; <= 0 skips it)
     *  · -6 mmproj not loaded but prompt has images */
    @JvmStatic external fun generateStart(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>,
                                          nPredict: Int, assistantPrefixUtf8: ByteArray?, availMemBytes: Long,
                                          progress: ProgressCallback?): Int
    @JvmStatic external fun generateNext(ctx: Long): ByteArray?                  // UTF-8 piece (may be empty); null == finished
    @JvmStatic external fun generateFinishReason(ctx: Long): Int                 // NFinish.*
    @JvmStatic external fun generateStats(ctx: Long): DoubleArray                // GStat.*
    /** Any thread. Sets the singleton's atomic cancel flag only; takes no handle and dereferences nothing. */
    @JvmStatic external fun cancel()

    // kv cache
    @JvmStatic external fun kvClear(ctx: Long)                                   // also drops all state checkpoints
    @JvmStatic external fun kvUsedTokens(ctx: Long): Int                         // cached prompt+generated cells
    @JvmStatic external fun kvNPast(ctx: Long): Int                              // positions used (differs from tokens for M-RoPE)

    // benchmark: [ppTps, tgTps, ppMs, tgMs]; clears KV; progress (done,total,BENCH) over reps
    @JvmStatic external fun bench(ctx: Long, nPrompt: Int, nGen: Int, reps: Int, progress: ProgressCallback?): DoubleArray   // empty array on error / cancel (lastError)

    @JvmStatic external fun lastError(): ByteArray                               // raw UTF-8 (may echo file names); any thread

    /** Kotlin-side helpers (not JNI). */
    fun utf8(bytes: ByteArray?): String = bytes?.let { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(it)).toString() } ?: ""
    fun lastErrorString(): String = utf8(lastError())
}
