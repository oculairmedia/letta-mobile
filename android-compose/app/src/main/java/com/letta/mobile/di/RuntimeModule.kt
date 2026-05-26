package com.letta.mobile.di

import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomMemFsStore
import com.letta.mobile.data.local.RoomRuntimeEventOutbox
import com.letta.mobile.data.session.LocalRuntimeProvider
import com.letta.mobile.runtime.local.AndroidLettaCodeHeadlessClient
import com.letta.mobile.runtime.local.AndroidLettaCodeRuntimeController
import com.letta.mobile.runtime.local.LettaCodeHeadlessClient
import com.letta.mobile.runtime.local.LettaCodeNodeBridge
import com.letta.mobile.runtime.local.LettaCodeRuntimeController
import com.letta.mobile.runtime.local.LocalKoogRuntimeProvider
import com.letta.mobile.runtime.local.LocalLettaCodeRuntimeProvider
import com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventOutbox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
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
    fun provideLettaCodeHeadlessClient(client: AndroidLettaCodeHeadlessClient): LettaCodeHeadlessClient = client

    @Provides
    @Singleton
    fun provideLettaCodeRuntimeController(controller: AndroidLettaCodeRuntimeController): LettaCodeRuntimeController =
        controller

    @Provides
    @Singleton
    fun provideLettaCodeNodeBridge(bridge: NativeLettaCodeNodeBridge): LettaCodeNodeBridge = bridge

    @Provides
    @IntoSet
    @Singleton
    fun provideLocalLettaCodeRuntimeProvider(provider: LocalLettaCodeRuntimeProvider): LocalRuntimeProvider = provider

    @Provides
    @IntoSet
    @Singleton
    fun provideLocalKoogRuntimeProvider(provider: LocalKoogRuntimeProvider): LocalRuntimeProvider = provider
}
