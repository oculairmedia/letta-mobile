package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-local Room database.
 *
 * Migration policy: production builders must register explicit migrations and must not use
 * destructive fallback. Pending local messages can contain user image bubbles that are not
 * recoverable from the server, so schema changes must preserve existing rows. Schema JSON is
 * exported under core/schemas and migration coverage lives in LettaDatabaseMigrationTest.
 */
@Database(
    entities = [
        AgentEntity::class,
        BugReportEntity::class,
        PendingLocalEntity::class,
        ConversationEntity::class,
        ConversationRefreshEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LettaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun bugReportDao(): BugReportDao
    abstract fun pendingLocalDao(): PendingLocalDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: LettaDatabase? = null

        fun getDatabase(context: Context): LettaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LettaDatabase::class.java,
                    "letta_database"
                )
                    .addMigrations(*LettaDatabaseMigrations.ALL)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
