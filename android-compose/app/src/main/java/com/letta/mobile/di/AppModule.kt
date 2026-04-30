package com.letta.mobile.di

import com.letta.mobile.clientmode.ClientModeSuppressionGate
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.timeline.SubscriberSuppressionGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindBlockRepository(impl: BlockRepository): IBlockRepository

    /**
     * Bind the Client-Mode-aware gate so [com.letta.mobile.data.timeline.TimelineRepository]
     * suppresses its direct-Letta SSE subscriber on conversations driven by
     * the WS gateway. Eliminates the doubled-response bubble — see
     * [ClientModeSuppressionGate] kdoc and
     * `plans/2026-04-clientmode-double-bubble-fix.md`.
     */
    @Binds
    @Singleton
    abstract fun bindSubscriberSuppressionGate(
        impl: ClientModeSuppressionGate,
    ): SubscriberSuppressionGate
}
