package to.eyed.inferno.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,                 // UUID
    val title: String,                          // "" until first message (UI shows "New chat")
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val modelId: String?,                       // model used last
    val trimmedBefore: Int = 0,                 // orderIndex of the first message still inside the context window
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId", "orderIndex")],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class MessageEntity(
    @PrimaryKey val id: String,                 // UUID
    val conversationId: String,
    val orderIndex: Int,
    val role: String,                           // "user" | "assistant" | "summary" (ChatRepository.ROLE_*)
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
    val finishReason: String? = null,           // FinishReason.name for assistant messages; null on an assistant row == still streaming or interrupted by process death (UI shows "· interrupted")
    val promptTokens: Int = 0,
    val reusedTokens: Int = 0,
    val generatedTokens: Int = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val imageEncodeMs: Long = 0,
    val kvUsedTokens: Int = 0,                  // KV cells after this turn (drives the context ring for this chat, 5.5)
    val nCtx: Int = 0,
    val paramsJson: String? = null,             // GenerationParams actually used (JSON)
    val templateName: String? = null,
    val templateSupported: Boolean = true,
    val modelId: String? = null,
    // ---- v2: context compaction (ContextPolicy.COMPACT). Only meaningful on role == "summary" rows. ----
    /** orderIndex of the last message this summary covers; -1 = a summary carried over into a fresh chat (covers nothing here). */
    @ColumnInfo(defaultValue = "-1") val compactedThrough: Int = -1,
    /** Raw messages folded into this summary, cumulative across chained compactions ("Compacted N messages"). */
    @ColumnInfo(defaultValue = "0") val compactedCount: Int = 0,
    // ---- v3 ----
    /** Wall time the model spent inside its think tags before the first visible token ("Thought for 12 s"); 0 = no reasoning. */
    @ColumnInfo(defaultValue = "0") val thinkingMs: Long = 0,
)

@Entity(
    tableName = "message_images",
    primaryKeys = ["messageId", "imageId"],
    indices = [Index("messageId")],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class MessageImageEntity(
    val messageId: String,
    val imageId: String,                        // sha256 hex == file name stem under files/images
    val orderIndex: Int,
    val width: Int,
    val height: Int,
)

data class MessageWithImages(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId") val images: List<MessageImageEntity>,
)

/**
 * One finished text-to-image generation. The PNG lives at [path] (files/images/gen/<id>.png) and its
 * 256 px thumb at [thumbPath]; deleting the row does not delete the files (ImageUtil.deleteGenerated does).
 * Generated images are not attachments: they never get a message_images row unless the user picks
 * "Use as attachment", which imports the PNG like any other picked image (new sha256 id under files/images).
 */
@Entity(tableName = "generated_images", indices = [Index("createdAt")])
data class GeneratedImageEntity(
    @PrimaryKey val id: String,                 // UUID == file name stem
    val modelId: String,                        // ImageModelSpec.id
    val prompt: String,
    val negative: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long,
    val sampler: String,                        // ImageSampler.name
    val totalMs: Long,
    val createdAt: Long,
    val path: String,
    val thumbPath: String,
)
