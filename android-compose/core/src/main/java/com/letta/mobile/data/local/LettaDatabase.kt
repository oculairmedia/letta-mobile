package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.letta.mobile.data.model.DomainIdConverters

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
        RuntimeEventEntity::class,
        MemFsFileEntity::class,
        MemFsCommitEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@androidx.room.TypeConverters(DomainIdConverters::class)
abstract class LettaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun bugReportDao(): BugReportDao
    abstract fun pendingLocalDao(): PendingLocalDao
    abstract fun conversationDao(): ConversationDao
    abstract fun runtimeEventDao(): RuntimeEventDao
    abstract fun memFsDao(): MemFsDao

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
