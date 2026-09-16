package to.eyed.inferno.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY orderIndex")
    fun messages(id: String): Flow<List<MessageWithImages>>

    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY orderIndex")
    suspend fun messagesOnce(id: String): List<MessageWithImages>

    @Upsert suspend fun upsert(c: ConversationEntity)
    @Upsert suspend fun upsert(m: MessageEntity)
    @Upsert suspend fun upsertImages(i: List<MessageImageEntity>)

    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :cid AND orderIndex >= :from") suspend fun deleteFrom(cid: String, from: Int)
    @Query("DELETE FROM conversations") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM message_images WHERE imageId = :imageId") suspend fun imageRefs(imageId: String): Int
    @Query("UPDATE conversations SET title = :title, updatedAt = :at WHERE id = :id") suspend fun rename(id: String, title: String, at: Long)
    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
    @Query("UPDATE conversations SET archived = :archived WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean)
    @Query("UPDATE conversations SET trimmedBefore = :idx WHERE id = :id") suspend fun setTrimmedBefore(id: String, idx: Int)

    // ---- WP4 additions (not in the 5.4 contract; needed by ChatRepository) ----
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: String): ConversationEntity?
    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE conversations SET modelId = :modelId, updatedAt = :at WHERE id = :id") suspend fun setModel(id: String, modelId: String?, at: Long)

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun messageById(id: String): MessageWithImages?

    /** -1 for an empty conversation, so the next orderIndex is always max + 1 == 0 for the first message. */
    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM messages WHERE conversationId = :cid") suspend fun maxOrderIndex(cid: String): Int

    /** Image ids attached to messages at or after [from] (collected BEFORE deleteFrom so the files can be released). */
    @Query("SELECT DISTINCT imageId FROM message_images WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :cid AND orderIndex >= :from)")
    suspend fun imageIdsFrom(cid: String, from: Int): List<String>

    @Query("SELECT DISTINCT imageId FROM message_images") suspend fun allImageIds(): List<String>

    /** Message row + its image rows in one transaction so a reader never sees a message without its attachments. */
    @Transaction
    suspend fun insertMessage(m: MessageEntity, images: List<MessageImageEntity>) {
        upsert(m)
        if (images.isNotEmpty()) upsertImages(images)
    }
}

/** Gallery of past text-to-image generations (12.5). Files are managed by ImageUtil; rows by WP9b's repository. */
@Dao
interface GeneratedImageDao {
    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC") fun observeAll(): Flow<List<GeneratedImageEntity>>
    @Upsert suspend fun insert(image: GeneratedImageEntity)
    @Query("DELETE FROM generated_images WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM generated_images WHERE id = :id") suspend fun byId(id: String): GeneratedImageEntity?
    @Query("SELECT COUNT(*) FROM generated_images") suspend fun count(): Int
}
