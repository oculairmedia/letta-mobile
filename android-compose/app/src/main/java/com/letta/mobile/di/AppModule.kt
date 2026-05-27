package com.letta.mobile.di

import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.channel.ChannelSyncStateStore
import com.letta.mobile.channel.IChannelNotificationPublisher
import com.letta.mobile.channel.IChannelSyncStateStore
import com.letta.mobile.chat.BuildConfigChatClientVersionProvider
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.health.ServerHealthRepository
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IArchiveRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.api.IGroupRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
import com.letta.mobile.data.repository.api.IJobRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.session.SessionScopedAgentRepository
import com.letta.mobile.data.session.SessionScopedAllConversationsRepository
import com.letta.mobile.data.session.SessionScopedArchiveRepository
import com.letta.mobile.data.session.SessionScopedChannelTransport
import com.letta.mobile.data.session.SessionScopedConversationRepository
import com.letta.mobile.data.session.SessionScopedCronRepository
import com.letta.mobile.data.session.SessionScopedFolderRepository
import com.letta.mobile.data.session.SessionScopedGroupRepository
import com.letta.mobile.data.session.SessionScopedIdentityRepository
import com.letta.mobile.data.session.SessionScopedJobRepository
import com.letta.mobile.data.session.SessionScopedMcpServerRepository
import com.letta.mobile.data.session.SessionScopedModelRepository
import com.letta.mobile.data.session.SessionScopedPassageRepository
import com.letta.mobile.data.session.SessionScopedProviderRepository
import com.letta.mobile.data.session.SessionScopedProjectRepository
import com.letta.mobile.data.session.SessionScopedProjectWorkRepository
import com.letta.mobile.data.session.SessionScopedRunRepository
import com.letta.mobile.data.session.SessionScopedScheduleRepository
import com.letta.mobile.data.session.SessionScopedStepRepository
import com.letta.mobile.data.session.SessionScopedToolRepository
import com.letta.mobile.data.session.SessionScopedVibesyncEventStreamRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.DataStoreRunCursorStore
import com.letta.mobile.data.transport.RunCursorStore
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
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindBlockRepository(impl: BlockRepository): IBlockRepository

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindBlockBackendScopedCache(impl: BlockRepository): BackendScopedCache

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepository): ISettingsRepository

    @Binds
    @Singleton
    abstract fun bindChannelTransport(impl: SessionScopedChannelTransport): IChannelTransport

    // letta-mobile-2rkdj — persisted run cursor map for reconnect resume.
    @Binds
    @Singleton
    abstract fun bindRunCursorStore(impl: DataStoreRunCursorStore): RunCursorStore

    @Binds
    @Singleton
    abstract fun bindCronRepository(impl: SessionScopedCronRepository): ICronRepository

    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: SessionScopedAgentRepository): IAgentRepository

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAgentBackendScopedCache(impl: SessionScopedAgentRepository): BackendScopedCache

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: SessionScopedConversationRepository): IConversationRepository

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindConversationBackendScopedCache(impl: SessionScopedConversationRepository): BackendScopedCache

    @Binds
    @Singleton
    abstract fun bindConversationInspectorMessageRepository(
        impl: MessageRepository,
    ): IConversationInspectorMessageRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepository): IMessageRepository

    @Binds
    @Singleton
    abstract fun bindAllConversationsRepository(impl: SessionScopedAllConversationsRepository): IAllConversationsRepository

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAllConversationsBackendScopedCache(impl: SessionScopedAllConversationsRepository): BackendScopedCache

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: SessionScopedProjectRepository): IProjectRepository

    @Binds
    @Singleton
    abstract fun bindProjectWorkRepository(impl: SessionScopedProjectWorkRepository): IProjectWorkRepository

    @Binds
    @Singleton
    abstract fun bindVibesyncEventStreamRepository(
        impl: SessionScopedVibesyncEventStreamRepository,
    ): IVibesyncEventStreamRepository

    @Binds
    @Singleton
    abstract fun bindToolRepository(impl: SessionScopedToolRepository): IToolRepository

    @Binds
    @Singleton
    abstract fun bindArchiveRepository(impl: SessionScopedArchiveRepository): IArchiveRepository

    @Binds
    @Singleton
    abstract fun bindBugReportRepository(impl: BugReportRepository): IBugReportRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: SessionScopedFolderRepository): IFolderRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: SessionScopedGroupRepository): IGroupRepository

    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: SessionScopedIdentityRepository): IIdentityRepository

    @Binds
    @Singleton
    abstract fun bindJobRepository(impl: SessionScopedJobRepository): IJobRepository

    @Binds
    @Singleton
    abstract fun bindMcpServerRepository(impl: SessionScopedMcpServerRepository): IMcpServerRepository

    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: SessionScopedModelRepository): IModelRepository

    @Binds
    @Singleton
    abstract fun bindPassageRepository(impl: SessionScopedPassageRepository): IPassageRepository

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPassageBackendScopedCache(impl: SessionScopedPassageRepository): BackendScopedCache

    @Binds
    @Singleton
    abstract fun bindProviderRepository(impl: SessionScopedProviderRepository): IProviderRepository

    @Binds
    @Singleton
    abstract fun bindRunRepository(impl: SessionScopedRunRepository): IRunRepository

    @Binds
    @Singleton
    abstract fun bindScheduleRepository(impl: SessionScopedScheduleRepository): IScheduleRepository

    @Binds
    @Singleton
    abstract fun bindStepRepository(impl: SessionScopedStepRepository): IStepRepository

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
    abstract fun bindTimelineExternalTransportWriter(impl: TimelineRepository): TimelineExternalTransportWriter

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindTimelineBackendScopedCache(impl: TimelineRepository): BackendScopedCache

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
