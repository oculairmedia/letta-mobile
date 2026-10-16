package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.letta.mobile.data.model.DomainIdConverters

/**
 * App-local Room database: caches (agents, conversations), the runtime-event outbox, memfs, bug
 * reports and the canvas op journal. Chat history is not here; it lives in the canonical
 * timeline ledger (`timeline-ledger.db`, [TimelineLedgerDatabase]).
 *
 * Migration policy (letta-mobile-by4vm.15, while the app has no users): schema changes bump the
 * version and reset this database destructively; nothing in it is unrecoverable from the backend.
 * v24 squashed v1..v23 and dropped the legacy chat-history tables (confirmed/normalized snapshots,
 * pending_local_messages, backend_conversation_cursors). Revisit before the first release with
 * users: from then on every schema change needs an explicit migration again.
 */
@Database(
    entities = [
        AgentEntity::class,
        BugReportEntity::class,
        ConversationEntity::class,
        ConversationRefreshEntity::class,
        RuntimeEventEntity::class,
        CanvasOpEntity::class,
    ],
    version = 25,
    exportSchema = true,
)
@androidx.room.TypeConverters(DomainIdConverters::class)
abstract class LettaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun bugReportDao(): BugReportDao
    abstract fun conversationDao(): ConversationDao
    abstract fun runtimeEventDao(): RuntimeEventDao
    abstract fun canvasOpDao(): CanvasOpDao

    companion object {
        @Volatile
        private var INSTANCE: LettaDatabase? = null

        fun getDatabase(context: Context): LettaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = builder(context, "letta_database").build()
                INSTANCE = instance
                instance
            }
        }

        /** The production builder: any version without a path to [LettaDatabase]'s resets. */
        internal fun builder(context: Context, name: String): RoomDatabase.Builder<LettaDatabase> =
            Room.databaseBuilder(context.applicationContext, LettaDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
    }
}
