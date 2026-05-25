package com.letta.mobile.di

import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.InMemoryRuntimeEventOutbox
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOutbox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {
    @Provides
    @Singleton
    fun provideRuntimeEventOutbox(): RuntimeEventOutbox =
        InMemoryRuntimeEventOutbox(
            eventIdFactory = { _, offset -> RuntimeEventId("runtime-event-${offset.value}") },
            clock = { EpochMillis(System.currentTimeMillis()) },
        )
}
