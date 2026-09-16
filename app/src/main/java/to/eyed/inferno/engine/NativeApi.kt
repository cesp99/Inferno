package to.eyed.inferno.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.LocalModel

/**
 * UTF-8 decoding with REPLACE (same policy as LlamaNative.utf8) that does not touch the LlamaNative object:
 * referencing it runs System.loadLibrary("inferno"), which the host-JVM unit tests cannot do.
 */
internal object Utf8 {
    fun decode(bytes: ByteArray?): String = bytes?.let {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(it)).toString()
    } ?: ""
}

/**
 * The subset of [LlamaNative] that InferenceEngine uses, behind an interface so the generate() state machine
 * (exactly one terminal event per flow, cancel joins the native loop, watchdog paths) can be unit-tested on the
 * host JVM with a scripted fake. [LlamaNativeApi] is the production binding; it touches the LlamaNative object
 * (and therefore System.loadLibrary) only on the first call.
 */
internal interface NativeApi {
    fun backendInit(minLogPriority: Int, bigMask: Int)
    fun systemInfo(): String
    /** Developer-mode extras with no-op defaults so the scripted test fake stays untouched. */
    fun setLogPriority(minLogPriority: Int) {}
    fun modelBufferTypes(): String = ""
    fun modelLoad(path: String, mmprojPath: String?, useMmap: Boolean, nThreadsMmproj: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Long
    fun modelFree(model: Long)
    fun modelInfoNumbers(model: Long): LongArray
    fun modelInfoStrings(model: Long): Array<ByteArray>
    fun mmprojLoad(model: Long, mmprojPath: String, nThreads: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Boolean
    fun mmprojFree(model: Long)
    fun mmprojLoaded(model: Long): Boolean
    fun contextCreate(model: Long, params: IntArray): Long
    fun contextFree(ctx: Long)
    fun contextNCtx(ctx: Long): Int
    fun contextSetThreads(ctx: Long, nGen: Int, nBatch: Int, bigCoresOnly: Boolean, poll: Int): Boolean
    fun estimateMemory(modelPath: String, mmprojPath: String?, params: IntArray): LongArray
    fun estimateCacheClear()
    fun samplerSet(ctx: Long, f: FloatArray, i: IntArray, grammar: String?): Boolean
    fun promptTokenCount(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>): Int
    fun generateStart(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>, nPredict: Int,
                      assistantPrefixUtf8: ByteArray?, availMemBytes: Long, progress: ProgressCallback?): Int
    fun generateNext(ctx: Long): ByteArray?
    fun generateFinishReason(ctx: Long): Int
    fun generateStats(ctx: Long): DoubleArray
    fun cancel()
    fun kvClear(ctx: Long)
    fun kvUsedTokens(ctx: Long): Int
    fun bench(ctx: Long, nPrompt: Int, nGen: Int, reps: Int, progress: ProgressCallback?): DoubleArray
    fun lastErrorString(): String
}

internal object LlamaNativeApi : NativeApi {
    override fun backendInit(minLogPriority: Int, bigMask: Int) = LlamaNative.backendInit(minLogPriority, bigMask)
    override fun systemInfo(): String = LlamaNative.systemInfo()
    override fun setLogPriority(minLogPriority: Int) = LlamaNative.setLogPriority(minLogPriority)
    override fun modelBufferTypes(): String = LlamaNative.modelBufferTypes()
    override fun modelLoad(path: String, mmprojPath: String?, useMmap: Boolean, nThreadsMmproj: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Long =
        LlamaNative.modelLoad(path, mmprojPath, useMmap, nThreadsMmproj, imageMinTokens, imageMaxTokens, progress)
    override fun modelFree(model: Long) = LlamaNative.modelFree(model)
    override fun modelInfoNumbers(model: Long): LongArray = LlamaNative.modelInfoNumbers(model)
    override fun modelInfoStrings(model: Long): Array<ByteArray> = LlamaNative.modelInfoStrings(model)
    override fun mmprojLoad(model: Long, mmprojPath: String, nThreads: Int, imageMinTokens: Int, imageMaxTokens: Int, progress: ProgressCallback?): Boolean =
        LlamaNative.mmprojLoad(model, mmprojPath, nThreads, imageMinTokens, imageMaxTokens, progress)
    override fun mmprojFree(model: Long) = LlamaNative.mmprojFree(model)
    override fun mmprojLoaded(model: Long): Boolean = LlamaNative.mmprojLoaded(model)
    override fun contextCreate(model: Long, params: IntArray): Long = LlamaNative.contextCreate(model, params)
    override fun contextFree(ctx: Long) = LlamaNative.contextFree(ctx)
    override fun contextNCtx(ctx: Long): Int = LlamaNative.contextNCtx(ctx)
    override fun contextSetThreads(ctx: Long, nGen: Int, nBatch: Int, bigCoresOnly: Boolean, poll: Int): Boolean =
        LlamaNative.contextSetThreads(ctx, nGen, nBatch, bigCoresOnly, poll)
    override fun estimateMemory(modelPath: String, mmprojPath: String?, params: IntArray): LongArray = LlamaNative.estimateMemory(modelPath, mmprojPath, params)
    override fun estimateCacheClear() = LlamaNative.estimateCacheClear()
    override fun samplerSet(ctx: Long, f: FloatArray, i: IntArray, grammar: String?): Boolean = LlamaNative.samplerSet(ctx, f, i, grammar)
    override fun promptTokenCount(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>): Int = LlamaNative.promptTokenCount(ctx, messages, images)
    override fun generateStart(ctx: Long, messages: Array<NativeChatMessage>, images: Array<NativeImage>, nPredict: Int,
                               assistantPrefixUtf8: ByteArray?, availMemBytes: Long, progress: ProgressCallback?): Int =
        LlamaNative.generateStart(ctx, messages, images, nPredict, assistantPrefixUtf8, availMemBytes, progress)
    override fun generateNext(ctx: Long): ByteArray? = LlamaNative.generateNext(ctx)
    override fun generateFinishReason(ctx: Long): Int = LlamaNative.generateFinishReason(ctx)
    override fun generateStats(ctx: Long): DoubleArray = LlamaNative.generateStats(ctx)
    override fun cancel() = LlamaNative.cancel()
    override fun kvClear(ctx: Long) = LlamaNative.kvClear(ctx)
    override fun kvUsedTokens(ctx: Long): Int = LlamaNative.kvUsedTokens(ctx)
    override fun bench(ctx: Long, nPrompt: Int, nGen: Int, reps: Int, progress: ProgressCallback?): DoubleArray = LlamaNative.bench(ctx, nPrompt, nGen, reps, progress)
    override fun lastErrorString(): String = LlamaNative.lastErrorString()
}

// ------------------------------------------------------------------------------------------------------------
// Prompt marshalling helpers used by InferenceEngine (kept next to the native seam so the engine file stays small).

internal fun List<PromptMessage>.toNative(): Array<NativeChatMessage> =
    map { NativeChatMessage(it.role, it.content.toByteArray(Charsets.UTF_8), it.imageIds.toTypedArray()) }.toTypedArray()

internal fun List<PromptImage>.toNative(): Array<NativeImage> = map { NativeImage(it.id, it.width, it.height, it.rgb) }.toTypedArray()

/** Rejects images the native side would refuse or that would blow the encoder budget: real RGB888 bytes within the edge cap. */
internal fun validatePromptImages(model: LocalModel, images: List<PromptImage>, detail: ImageDetail) {
    val cap = ContextManager.imageEdgeCap(model, detail)
    for (img in images) {
        val rgb = img.rgb ?: throw EngineException("Image ${img.id.take(8)} has no pixel data")
        if (rgb.size != img.width * img.height * 3) throw EngineException("Image ${img.id.take(8)} is not packed RGB888")
        if (maxOf(img.width, img.height) > cap) throw EngineException("Image ${img.id.take(8)} exceeds the $cap px edge cap; pre-resize it")
    }
}

/** Gemma 4 thinking switch: "<|think|>" at the very start of the system message (hand-rolled formatter contract). */
internal const val GEMMA_THINK = "<|think|>"
internal fun withGemmaThinkFlag(messages: List<PromptMessage>): List<PromptMessage> {
    val first = messages.firstOrNull()
    return if (first != null && first.role == "system") {
        if (first.content.startsWith(GEMMA_THINK)) messages else listOf(first.copy(content = GEMMA_THINK + first.content)) + messages.drop(1)
    } else listOf(PromptMessage("system", GEMMA_THINK)) + messages
}
