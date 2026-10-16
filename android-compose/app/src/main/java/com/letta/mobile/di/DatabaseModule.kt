package com.letta.mobile.di

import android.content.Context
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.MemFsDao
import com.letta.mobile.data.local.RuntimeEventDao
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.local.CanvasOpDao
import com.letta.mobile.data.local.RoomCanvasOpLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LettaDatabase {
        return LettaDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideTimelineLedgerDatabase(@ApplicationContext context: Context): com.letta.mobile.data.local.TimelineLedgerDatabase =
        androidx.room.Room.databaseBuilder(
            context,
            com.letta.mobile.data.local.TimelineLedgerDatabase::class.java,
            "timeline-ledger.db",
        ).addMigrations(
            com.letta.mobile.data.local.TimelineLedgerDatabase.MIGRATION_1_2,
            com.letta.mobile.data.local.TimelineLedgerDatabase.MIGRATION_2_3,
            com.letta.mobile.data.local.TimelineLedgerDatabase.MIGRATION_3_4,
        ).build()

    @Provides
    @Singleton
    fun provideTimelineOwnership(@ApplicationContext context: Context): com.letta.mobile.data.local.TimelineOwnershipAuthority =
        com.letta.mobile.data.local.TimelineOwnershipAuthority(context.noBackupFilesDir.toPath().resolve("timeline-ownership"))

    @Provides
    @Singleton
    fun provideOwnedTimelineStorage(
        ledger: com.letta.mobile.data.local.TimelineLedgerDatabase,
        authority: com.letta.mobile.data.local.TimelineOwnershipAuthority,
    ): com.letta.mobile.data.local.TimelineOwnedStorageFactory =
        com.letta.mobile.data.local.TimelineOwnedStorageFactory(ledger, authority)

    // letta-mobile-g2ff0: DAOs returned directly here, but the LettaDatabase
    // singleton is the only thing that triggers Room.databaseBuilder.build().
    // Consumers (AgentRepository, ConversationRepository, etc.) take Lazy<T>
    // parameters and call .get() inside their existing repositoryScope.launch {}
    // blocks — so the DB init happens off the main thread, lazily, the first
    // time any DAO is actually accessed (typically on app foregrounding
    // navigation, not on Hilt graph resolution at app startup).
    @Provides
    fun provideAgentDao(database: LettaDatabase): AgentDao {
        return database.agentDao()
    }

    @Provides
    fun provideBugReportDao(database: LettaDatabase): BugReportDao {
        return database.bugReportDao()
    }

    @Provides
    fun provideConversationDao(database: LettaDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideRuntimeEventDao(database: LettaDatabase): RuntimeEventDao {
        return database.runtimeEventDao()
    }

    @Provides
    fun provideMemFsDao(database: LettaDatabase): MemFsDao {
        return database.memFsDao()
    }

    @Provides
    @Singleton
    fun provideNotebookLocalStore(@ApplicationContext context: Context): NotebookLocalStore {
        val prefs = context.getSharedPreferences("notebook-identity", Context.MODE_PRIVATE)
        val peerId = prefs.getString("peer-id", null) ?: java.util.UUID.randomUUID().toString().also { id ->
            check(prefs.edit().putString("peer-id", id).commit()) { "Cannot persist notebook peer identity" }
        }
        val directory = context.filesDir.toPath().resolve("notebooks/documents")
        java.nio.file.Files.createDirectories(directory)
        return NotebookLocalStore(directory, peerId)
    }

    @Provides
    @Singleton
    fun provideCanvasDocumentStore(notebooks: NotebookLocalStore): CanvasDocumentStore =
        NotebookCanvasDocumentStore(notebooks)

    @Provides
    fun provideCanvasOpDao(database: LettaDatabase): CanvasOpDao {
        return database.canvasOpDao()
    }

    /**
     * The canvas op log is the notebook document's write-ahead journal (letta-mobile-by4vm.22):
     * the notebook store owns the document, CanvasSession settles each op once it is stored.
     */
    @Provides
    @Singleton
    fun provideCanvasOpLog(dao: CanvasOpDao): CanvasOpLog {
        return RoomCanvasOpLog(dao)
    }
}
