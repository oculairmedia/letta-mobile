package com.letta.mobile.di

import android.content.Context
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.ConversationCursorDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.MemFsDao
import com.letta.mobile.data.local.PendingLocalDao
import com.letta.mobile.data.local.RoomConversationCursorStore
import com.letta.mobile.data.local.RoomPendingLocalStore
import com.letta.mobile.data.local.RuntimeEventDao
import com.letta.mobile.data.local.ConfirmedTimelineSnapshotDao
import com.letta.mobile.data.local.RoomConfirmedTimelineStore
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.data.timeline.PendingLocalStore
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PendingLocalStoreModule {
    @Binds
    @Singleton
    abstract fun bindPendingLocalStore(impl: RoomPendingLocalStore): PendingLocalStore

    @Binds
    @Singleton
    abstract fun bindConversationCursorStore(impl: RoomConversationCursorStore): ConversationCursorStore

    @Binds
    @Singleton
    abstract fun bindConfirmedTimelineStore(impl: RoomConfirmedTimelineStore): ConfirmedTimelineStore
}

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
    fun provideRoomConfirmedTimelineStore(database: LettaDatabase): RoomConfirmedTimelineStore {
        return RoomConfirmedTimelineStore(database)
    }

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
    fun providePendingLocalDao(database: LettaDatabase): PendingLocalDao {
        return database.pendingLocalDao()
    }

    @Provides
    fun provideConversationDao(database: LettaDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideConversationCursorDao(database: LettaDatabase): ConversationCursorDao {
        return database.conversationCursorDao()
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
    fun provideConfirmedTimelineSnapshotDao(database: LettaDatabase): ConfirmedTimelineSnapshotDao {
        return database.confirmedTimelineSnapshotDao()
    }
}
