package com.letta.mobile.di

import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomMemFsStore
import com.letta.mobile.data.local.RoomRuntimeEventOutbox
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {
    @Provides
    @Singleton
    fun provideRuntimeEventOutbox(database: LettaDatabase): RuntimeEventOutbox =
        RoomRuntimeEventOutbox(database)

    @Provides
    @Singleton
    fun provideMemFsStore(database: LettaDatabase): MemFsStore =
        RoomMemFsStore(database)

    @Provides
    @Singleton
    fun provideTurnEngine(): TurnEngine = TurnEngine { command ->
        flowOf(
            RuntimeEventDraft(
                backendId = command.backendId,
                runtimeId = command.runtimeId,
                agentId = command.agentId,
                conversationId = command.conversationId,
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.RunLifecycleChanged(
                    status = RuntimeRunStatus.Failed,
                    reason = "Koog TurnEngine adapter is not configured yet.",
                ),
            )
        )
    }
}
