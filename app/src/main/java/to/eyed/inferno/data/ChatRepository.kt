package to.eyed.inferno.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.eyed.inferno.data.db.ChatDao
import to.eyed.inferno.data.db.ConversationEntity
import to.eyed.inferno.data.db.MessageEntity
import to.eyed.inferno.data.db.MessageImageEntity
import to.eyed.inferno.data.db.MessageWithImages
import java.util.UUID

data class Attachment(val id: String, val path: String, val thumbPath: String, val width: Int, val height: Int)
data class ChatMessage(val id: String, val role: String, val content: String, val thinking: String?, val createdAt: Long,
    val images: List<Attachment>, val stats: MessageStats?, val orderIndex: Int,
    /** Summary rows only (ContextPolicy.COMPACT): orderIndex of the last message covered (-1 = carried over) and the cumulative count. */
    val compactedThrough: Int = -1, val compactedCount: Int = 0) {
    val isSummary get() = role == ChatRepository.ROLE_SUMMARY
}
/** Everything the TurnDetailsSheet shows, persisted per assistant message (no LoadedModel dependency, so old turns and reopened chats have full details). Rows with 0 / null are hidden. */
data class MessageStats(
    val promptTokens: Int, val reusedTokens: Int = 0, val generatedTokens: Int, val prefillMs: Long, val decodeMs: Long,
    val imageEncodeMs: Long = 0, val kvUsedTokens: Int = 0, val nCtx: Int = 0, val thinkingMs: Long = 0,
    val paramsJson: String? = null, val templateName: String? = null, val templateSupported: Boolean = true,
    val finishReason: String?, val modelId: String?,
) {
    val decodeTps get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
    val prefillTps get() = if (prefillMs > 0) (promptTokens - reusedTokens) * 1000.0 / prefillMs else 0.0
    val interrupted get() = finishReason == null
}
data class Conversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val pinned: Boolean, val archived: Boolean, val modelId: String?, val trimmedBefore: Int) {
    val displayTitle get() = title.ifBlank { "New chat" }
}

/**
 * The only writer of the chat tables. Attachment files are reference-counted through message_images: every
 * delete path asks the DAO for the remaining refs and lets ImageUtil drop the files at zero.
 */
class ChatRepository(private val dao: ChatDao, private val imageUtil: ImageUtil) {
    val conversations: Flow<List<Conversation>> = dao.conversations().map { list -> list.map { it.toConversation() } }

    /**
     * Image ids [truncateFrom] unreferenced but did not release yet. ChatViewModel.editAndResend truncates from the
     * edited user message and then re-attaches its images through [appendUser]; releasing them inside truncateFrom
     * would delete the very files the new row points at. So the release is deferred to the next write, by which
     * time the re-attached ids have a reference again and only the truly orphaned ones go.
     */
    private val heldBack = LinkedHashSet<String>()

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        dao.messages(conversationId).map { rows -> rows.map { it.toChatMessage() } }

    suspend fun messagesOnce(conversationId: String): List<ChatMessage> = dao.messagesOnce(conversationId).map { it.toChatMessage() }

    suspend fun create(modelId: String?): Conversation {
        val now = System.currentTimeMillis()
        val c = ConversationEntity(id = UUID.randomUUID().toString(), title = "", createdAt = now, updatedAt = now, modelId = modelId)
        dao.upsert(c)
        return c.toConversation()
    }

    suspend fun appendUser(conversationId: String, text: String, attachments: List<Attachment>): ChatMessage {
        val now = System.currentTimeMillis()
        val m = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = conversationId, orderIndex = dao.maxOrderIndex(conversationId) + 1,
            role = ROLE_USER, content = text, createdAt = now,
        )
        val rows = attachments.distinctBy { it.id }.mapIndexed { i, a -> MessageImageEntity(m.id, a.id, i, a.width, a.height) }
        dao.insertMessage(m, rows)
        val conv = dao.conversation(conversationId)
        if (conv != null && conv.title.isBlank() && text.isNotBlank()) dao.rename(conversationId, titleFrom(text), now)
        else dao.touch(conversationId, now)
        flushHeldBack()     // after the insert: ids re-attached by an edit have their reference back
        return MessageWithImages(m, rows).toChatMessage()
    }

    /** Creates the assistant row at the FIRST token with finishReason = null (== streaming / interrupted); ChatViewModel updates it every 2 s or 200 tokens. */
    suspend fun appendAssistant(conversationId: String, text: String, thinking: String?, stats: MessageStats?): ChatMessage {
        val now = System.currentTimeMillis()
        val base = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = conversationId, orderIndex = dao.maxOrderIndex(conversationId) + 1,
            role = ROLE_ASSISTANT, content = text, thinking = thinking, createdAt = now,
        )
        val m = if (stats != null) base.withStats(stats) else base
        dao.insertMessage(m, emptyList())
        if (stats?.modelId != null) dao.setModel(conversationId, stats.modelId, now) else dao.touch(conversationId, now)
        flushHeldBack()
        return MessageWithImages(m, emptyList()).toChatMessage()
    }

    /**
     * Compaction result (ContextPolicy.COMPACT): appended at the end like any message, but the UI draws it right after
     * orderIndex [compactedThrough] and the prompt builder replaces everything up to there with it. [compactedThrough]
     * -1 seeds a fresh chat with a summary carried over from another one.
     */
    suspend fun appendSummary(conversationId: String, text: String, compactedThrough: Int, compactedCount: Int): ChatMessage {
        val now = System.currentTimeMillis()
        val m = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = conversationId, orderIndex = dao.maxOrderIndex(conversationId) + 1,
            role = ROLE_SUMMARY, content = text, createdAt = now, compactedThrough = compactedThrough, compactedCount = compactedCount,
        )
        dao.insertMessage(m, emptyList())
        dao.touch(conversationId, now)
        flushHeldBack()
        return MessageWithImages(m, emptyList()).toChatMessage()
    }

    /** Periodic + final write; the final write sets stats.finishReason. A null [stats] keeps the stored numbers. */
    suspend fun updateAssistant(messageId: String, text: String, thinking: String?, stats: MessageStats?) {
        val cur = dao.messageById(messageId)?.message ?: return
        val next = (if (stats != null) cur.withStats(stats) else cur).copy(content = text, thinking = thinking)
        dao.upsert(next)
        // Only the final write bumps the sidebar order; the 2 s streaming writes would just churn the conversations Flow.
        if (stats?.finishReason != null) dao.touch(cur.conversationId, System.currentTimeMillis())
    }

    /**
     * Regenerate / edit: deletes messages with orderIndex >= [orderIndex]. Their images are released (when no other
     * message references them) by the next write, not here, so a caller may re-attach them with [appendUser] first.
     */
    suspend fun truncateFrom(conversationId: String, orderIndex: Int) {
        flushHeldBack()
        val ids = dao.imageIdsFrom(conversationId, orderIndex)
        dao.deleteFrom(conversationId, orderIndex)
        synchronized(heldBack) { heldBack += ids }
    }

    suspend fun message(messageId: String): ChatMessage? = dao.messageById(messageId)?.toChatMessage()

    suspend fun setTitle(conversationId: String, title: String) = dao.rename(conversationId, title.trim(), System.currentTimeMillis())
    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun setArchived(id: String, archived: Boolean) = dao.setArchived(id, archived)
    suspend fun setModel(conversationId: String, modelId: String?) = dao.setModel(conversationId, modelId, System.currentTimeMillis())

    suspend fun delete(conversationId: String) {
        val ids = dao.imageIdsFrom(conversationId, 0)
        dao.deleteConversation(conversationId)     // messages + message_images cascade
        releaseImages(ids)
        flushHeldBack()
    }

    suspend fun deleteAll() {
        val ids = dao.allImageIds()
        dao.deleteAll()
        releaseImages(ids)
        flushHeldBack()
    }

    suspend fun setTrimmedBefore(conversationId: String, orderIndex: Int) = dao.setTrimmedBefore(conversationId, orderIndex)
    suspend fun touch(conversationId: String) = dao.touch(conversationId, System.currentTimeMillis())

    private suspend fun releaseImages(ids: List<String>) {
        for (id in ids) imageUtil.deleteIfUnreferenced(id, dao.imageRefs(id))
    }

    /** Releases what [truncateFrom] left behind, minus anything a message references again by now. */
    private suspend fun flushHeldBack() {
        val ids = synchronized(heldBack) { heldBack.toList().also { heldBack.clear() } }
        if (ids.isNotEmpty()) releaseImages(ids)
    }

    private fun ConversationEntity.toConversation() = Conversation(id, title, createdAt, updatedAt, pinned, archived, modelId, trimmedBefore)

    private fun MessageWithImages.toChatMessage(): ChatMessage {
        val m = message
        val stats = if (m.role == ROLE_ASSISTANT) MessageStats(
            promptTokens = m.promptTokens, reusedTokens = m.reusedTokens, generatedTokens = m.generatedTokens, prefillMs = m.prefillMs,
            decodeMs = m.decodeMs, imageEncodeMs = m.imageEncodeMs, kvUsedTokens = m.kvUsedTokens, nCtx = m.nCtx, thinkingMs = m.thinkingMs, paramsJson = m.paramsJson,
            templateName = m.templateName, templateSupported = m.templateSupported, finishReason = m.finishReason, modelId = m.modelId,
        ) else null
        val atts = images.sortedBy { it.orderIndex }.map { imageUtil.attachment(it.imageId, it.width, it.height) }
        return ChatMessage(m.id, m.role, m.content, m.thinking, m.createdAt, atts, stats, m.orderIndex, m.compactedThrough, m.compactedCount)
    }

    private fun MessageEntity.withStats(s: MessageStats) = copy(
        finishReason = s.finishReason, promptTokens = s.promptTokens, reusedTokens = s.reusedTokens, generatedTokens = s.generatedTokens,
        prefillMs = s.prefillMs, decodeMs = s.decodeMs, imageEncodeMs = s.imageEncodeMs, kvUsedTokens = s.kvUsedTokens, nCtx = s.nCtx,
        thinkingMs = s.thinkingMs, paramsJson = s.paramsJson, templateName = s.templateName, templateSupported = s.templateSupported, modelId = s.modelId ?: modelId,
    )

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        /** Model-written summary of earlier turns (ContextPolicy.COMPACT); never sent as a chat turn, folded into the system note. */
        const val ROLE_SUMMARY = "summary"
        const val TITLE_MAX = 48

        /** Title rule (5.4): first non-blank line of the first user message, trimmed to 48 chars + "…". */
        fun titleFrom(text: String): String {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            return if (line.length <= TITLE_MAX) line else line.take(TITLE_MAX).trimEnd() + "…"
        }
    }
}
