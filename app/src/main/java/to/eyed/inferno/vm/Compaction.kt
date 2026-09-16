package to.eyed.inferno.vm

import kotlinx.coroutines.flow.Flow
import to.eyed.inferno.data.ChatMessage
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.EngineException
import to.eyed.inferno.engine.FinishReason
import to.eyed.inferno.engine.GenerationEvent
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.InferenceEngine
import to.eyed.inferno.engine.PromptMessage
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.models.ThinkingSpec

// Context compaction (ContextPolicy.COMPACT and the STOP panel's "Carry over a summary"): the model writes a summary
// of the older turns, persisted as a ROLE_SUMMARY row; the next prompts are system prompt + summary note + the turns
// after the compaction point. Kept free of Android and AppContainer so the flow runs under a JVM test over FakeNative.

/** A chat's memory: the latest summary (if any) and the turns after its compaction point, summary rows excluded. */
data class MemoryView(val summary: ChatMessage?, val recent: List<ChatMessage>) {
    /**
     * Turns as prompt messages (assistant turns are content only, never their reasoning). An assistant turn stopped
     * while it was still thinking has no content: it is left out rather than sent as an empty turn.
     */
    fun prompt(): List<PromptMessage> = recent.mapNotNull { m ->
        if (m.role == ChatRepository.ROLE_USER) PromptMessage("user", ContextManager.sanitize(m.content), m.images.map { it.id })
        else if (m.content.isNotBlank()) PromptMessage("assistant", m.content) else null
    }
    companion object {
        fun of(messages: List<ChatMessage>): MemoryView {
            val summary = messages.lastOrNull { it.isSummary }
            val recent = messages.filter { !it.isSummary && (summary == null || it.orderIndex > summary.compactedThrough) }
            return MemoryView(summary, recent)
        }
    }
}

/** The one engine call a compaction needs; [EngineCompaction] is the production binding, tests may fake it. */
interface CompactionEngine {
    fun generate(conversationId: String, request: List<PromptMessage>, maxTokens: Int): Flow<GenerationEvent>
}

/** The persistence a compaction needs (ChatRepository in production, an in-memory list in tests). */
interface CompactionStore {
    suspend fun messages(conversationId: String): List<ChatMessage>
    suspend fun appendSummary(conversationId: String, text: String, compactedThrough: Int, compactedCount: Int): ChatMessage
    /** A fresh summary makes the rolling window's persisted start meaningless; the compactor resets it. */
    suspend fun setTrimmedBefore(conversationId: String, orderIndex: Int)
}

/** InferenceEngine binding: low temperature, thinking off (through the model's own disable prefix), no images. */
class EngineCompaction(private val engine: InferenceEngine, private val thinking: ThinkingSpec, private val base: GenerationParams,
                       private val detail: ImageDetail) : CompactionEngine {
    override fun generate(conversationId: String, request: List<PromptMessage>, maxTokens: Int): Flow<GenerationEvent> =
        engine.generate(conversationId, request, emptyList(), base.copy(temperature = ContextManager.COMPACT_TEMPERATURE, maxTokens = maxTokens),
            thinking, thinkingEnabled = false, imageDetail = detail)
}

class Compactor(private val cm: ContextManager, private val engine: CompactionEngine, private val store: CompactionStore) {
    data class Result(val summary: ChatMessage, val droppedMaterial: Int)

    /**
     * Runs one compaction on [conversationId] and persists the summary; null when there is nothing older than the kept
     * turns. Cancelling the caller aborts the native loop and persists nothing. [keepTurns] = 0 is the carry-over
     * variant (everything but an unanswered trailing question goes into the summary).
     */
    suspend fun compact(conversationId: String, systemPrompt: String?, nCtx: Int, reserve: Int,
                        keepTurns: Int = ContextManager.COMPACT_KEEP_TURNS): Result? {
        val view = MemoryView.of(store.messages(conversationId))
        val previous = view.summary?.content
        val plan = cm.planCompaction(ContextManager.systemWith(systemPrompt, null), previous, view.prompt(), nCtx, reserve, keepTurns) ?: return null
        val text = StringBuilder()
        engine.generate(conversationId, plan.request, plan.maxTokens).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> text.append(ev.text)
                is GenerationEvent.Done -> if (ev.reason == FinishReason.CANCELLED) throw EngineException("Compaction cancelled")
                is GenerationEvent.Error -> throw EngineException(ev.message)
                GenerationEvent.NeedsTruncation -> throw EngineException("The conversation is too long to summarize")
                else -> Unit
            }
        }
        val summary = text.toString().trim()
        if (summary.isEmpty()) throw EngineException("The model returned an empty summary")
        val through = view.recent[plan.cut - 1].orderIndex
        val count = plan.cut + (view.summary?.compactedCount ?: 0)
        val row = store.appendSummary(conversationId, summary, through, count)
        store.setTrimmedBefore(conversationId, 0)
        return Result(row, plan.droppedMaterial)
    }
}
