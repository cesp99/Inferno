package to.eyed.inferno.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 1 shipped with all four tables (chat + generated-image gallery); version 2 adds the compaction columns on
 * messages. Schema is exported to app/schemas so every next version MUST come with a Migration: there is
 * deliberately no fallbackToDestructiveMigration, losing a user's chats on update is never acceptable.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MessageImageEntity::class, GeneratedImageEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
    abstract fun generatedImages(): GeneratedImageDao

    companion object {
        const val NAME = "chats.db"

        /** v2: summary rows (ContextPolicy.COMPACT) record what they cover. Existing rows get the "none" defaults. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN compactedThrough INTEGER NOT NULL DEFAULT -1")
                connection.execSQL("ALTER TABLE messages ADD COLUMN compactedCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                // WAL (the default) lets the message Flow re-query while a streaming upsert is in flight.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Instrumented tests only. */
        fun inMemory(context: Context): ChatDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
