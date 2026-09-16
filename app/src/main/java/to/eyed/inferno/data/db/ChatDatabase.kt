package to.eyed.inferno.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1 ships with all four tables (chat + generated-image gallery). Schema is exported to app/schemas so the
 * next version MUST come with a Migration: there is deliberately no fallbackToDestructiveMigration, losing a user's
 * chats on update is never acceptable.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MessageImageEntity::class, GeneratedImageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
    abstract fun generatedImages(): GeneratedImageDao

    companion object {
        const val NAME = "chats.db"

        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                // WAL (the default) lets the message Flow re-query while a streaming upsert is in flight.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** Instrumented tests only. */
        fun inMemory(context: Context): ChatDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
