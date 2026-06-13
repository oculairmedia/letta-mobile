package com.letta.mobile.di

import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomMemFsStore
import com.letta.mobile.data.local.RoomRuntimeEventOutbox
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.session.LocalRuntimeProvider
import com.letta.mobile.runtime.local.EmbeddedCatalogModelSource
import com.letta.mobile.runtime.local.LettaCodeLocalBackendStore
import com.letta.mobile.runtime.local.AndroidLettaCodeHeadlessClient
import com.letta.mobile.runtime.local.AndroidLettaCodeRuntimeController
import com.letta.mobile.runtime.local.AndroidNetworkBridge
import com.letta.mobile.runtime.local.BuildConfigEmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.LettaCodeHeadlessClient
import com.letta.mobile.runtime.local.LettaCodeNodeBridge
import com.letta.mobile.runtime.local.LettaCodeRuntimeController
import com.letta.mobile.runtime.local.LiteRtLmOnDeviceChatCompletionEngine
import com.letta.mobile.runtime.local.LocalKoogRuntimeProvider
import com.letta.mobile.runtime.local.LocalLettaCodeRuntimeProvider
import com.letta.mobile.runtime.local.LocalAndroidNetworkBridge
import com.letta.mobile.runtime.local.LocalOpenAiOnDeviceBridge
import com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge
import com.letta.mobile.runtime.local.OnDeviceChatCompletionEngine
import com.letta.mobile.runtime.local.OnDeviceModelImporter
import com.letta.mobile.runtime.local.OnDeviceOpenAiBridge
import com.letta.mobile.runtime.local.SafOnDeviceModelImporter
import com.letta.mobile.runtime.local.modelcatalog.AssetEmbeddedModelRepository
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
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
    @Singleton
    fun provideOnDeviceOpenAiBridge(bridge: LocalOpenAiOnDeviceBridge): OnDeviceOpenAiBridge = bridge

    @Provides
    @Singleton
    fun provideAndroidNetworkBridge(bridge: LocalAndroidNetworkBridge): AndroidNetworkBridge = bridge

    @Provides
    @Singleton
    fun provideLocalRuntimeConversationSource(
        store: LettaCodeLocalBackendStore,
    ): LocalRuntimeConversationSource = store

    @Provides
    @Singleton
    fun provideLocalRuntimeAgentSource(
        store: LettaCodeLocalBackendStore,
    ): LocalRuntimeAgentSource = store

    @Provides
    @Singleton
    fun provideLocalRuntimeModelSource(
        source: EmbeddedCatalogModelSource,
    ): LocalRuntimeModelSource = source

    @Provides
    @Singleton
    fun provideOnDeviceChatCompletionEngine(
        engine: LiteRtLmOnDeviceChatCompletionEngine,
    ): OnDeviceChatCompletionEngine = engine

    @Provides
    @Singleton
    fun provideOnDeviceModelImporter(importer: SafOnDeviceModelImporter): OnDeviceModelImporter = importer

    @Provides
    @Singleton
    fun provideEmbeddedLettaCodeRuntimeStatusProvider(
        provider: BuildConfigEmbeddedLettaCodeRuntimeStatusProvider,
    ): EmbeddedLettaCodeRuntimeStatusProvider = provider

    @Provides
    @Singleton
    fun provideEmbeddedModelRepository(repository: AssetEmbeddedModelRepository): EmbeddedModelRepository = repository

    @Provides
    @IntoSet
    @Singleton
    fun provideLocalLettaCodeRuntimeProvider(provider: LocalLettaCodeRuntimeProvider): LocalRuntimeProvider = provider

    @Provides
    @IntoSet
    @Singleton
    fun provideLocalKoogRuntimeProvider(provider: LocalKoogRuntimeProvider): LocalRuntimeProvider = provider
}
