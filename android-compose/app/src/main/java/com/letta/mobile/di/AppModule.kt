package com.letta.mobile.di

import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.channel.ChannelSyncStateStore
import com.letta.mobile.channel.IChannelNotificationPublisher
import com.letta.mobile.channel.IChannelSyncStateStore
import com.letta.mobile.chat.BuildConfigChatClientVersionProvider
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.health.ServerHealthRepository
import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.api.TimelineClientModeWriter
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.feature.chat.ChatClientVersionProvider
import com.letta.mobile.platform.storage.AndroidAppPrivateStorageRootProvider
import com.letta.mobile.platform.storage.AndroidSafStorageGrantStore
import com.letta.mobile.platform.storage.AppPrivateStorageRootProvider
import com.letta.mobile.platform.storage.SafStorageGrantStore
import com.letta.mobile.platform.systemaccess.AndroidSystemAccessEnvironment
import com.letta.mobile.platform.systemaccess.DefaultSystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessEnvironment
import com.letta.mobile.startup.AppStartupActions
import com.letta.mobile.startup.DefaultAppStartupActions
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

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepository): ISettingsRepository

    @Binds
    @Singleton
    abstract fun bindChannelTransport(impl: ChannelTransport): IChannelTransport

    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: AgentRepository): IAgentRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepository): IConversationRepository

    @Binds
    @Singleton
    abstract fun bindConversationInspectorMessageRepository(
        impl: MessageRepository,
    ): IConversationInspectorMessageRepository

    @Binds
    @Singleton
    abstract fun bindServerHealthRepository(impl: ServerHealthRepository): IServerHealthRepository

    @Binds
    @Singleton
    abstract fun bindChannelSyncStateStore(impl: ChannelSyncStateStore): IChannelSyncStateStore

    @Binds
    @Singleton
    abstract fun bindChannelNotificationPublisher(
        impl: ChannelNotificationPublisher,
    ): IChannelNotificationPublisher

    @Binds
    @Singleton
    abstract fun bindTimelineClientModeWriter(impl: TimelineRepository): TimelineClientModeWriter

    @Binds
    @Singleton
    abstract fun bindTimelineExternalTransportWriter(impl: TimelineRepository): TimelineExternalTransportWriter

    @Binds
    @Singleton
    abstract fun bindSystemAccessEnvironment(impl: AndroidSystemAccessEnvironment): SystemAccessEnvironment

    @Binds
    @Singleton
    abstract fun bindSystemAccessCapabilityRegistry(
        impl: DefaultSystemAccessCapabilityRegistry,
    ): SystemAccessCapabilityRegistry

    @Binds
    @Singleton
    abstract fun bindAppPrivateStorageRootProvider(
        impl: AndroidAppPrivateStorageRootProvider,
    ): AppPrivateStorageRootProvider

    @Binds
    @Singleton
    abstract fun bindSafStorageGrantStore(impl: AndroidSafStorageGrantStore): SafStorageGrantStore

    @Binds
    @Singleton
    abstract fun bindNotificationDelivery(impl: NotificationDeliveryCoordinator): NotificationDelivery

    @Binds
    @Singleton
    abstract fun bindChatClientVersionProvider(
        impl: BuildConfigChatClientVersionProvider,
    ): ChatClientVersionProvider

    @Binds
    @Singleton
    internal abstract fun bindAppStartupActions(impl: DefaultAppStartupActions): AppStartupActions
}
