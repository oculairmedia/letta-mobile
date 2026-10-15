package com.letta.mobile.di

import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.channel.ChannelSyncStateStore
import com.letta.mobile.channel.IChannelNotificationPublisher
import com.letta.mobile.channel.IChannelSyncStateStore
import com.letta.mobile.chat.BuildConfigChatClientVersionProvider
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.relayTopicOf
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.health.ServerHealthRepository
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.IrohAdminRpcApprovalSource
import com.letta.mobile.data.repository.IrohAdminRpcBlockSource
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.SlashCommandRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
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
import com.letta.mobile.data.session.ProcessScope
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.session.SessionScopedChannelTransport
import com.letta.mobile.data.session.SessionScopedVibesyncEventStreamRepository
import com.letta.mobile.data.session.UNCONFIGURED_BACKEND_KEY
import com.letta.mobile.data.session.scopedAgentRepository
import com.letta.mobile.data.session.scopedAllConversationsRepository
import com.letta.mobile.data.session.sessionBackendScopedCaches
import com.letta.mobile.data.session.sessionScoped
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport
import com.letta.mobile.data.timeline.PendingLocalStore
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.feature.chat.coordination.ChatClientVersionProvider
import com.letta.mobile.platform.storage.AndroidAppPrivateStorageRootProvider
import com.letta.mobile.platform.storage.AndroidSafStorageGrantStore
import com.letta.mobile.platform.storage.AppPrivateStorageRootProvider
import com.letta.mobile.platform.storage.SafStorageGrantStore
import com.letta.mobile.platform.systemaccess.AndroidSystemAccessEnvironment
import com.letta.mobile.platform.systemaccess.DefaultSystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessEnvironment
import com.letta.mobile.runtime.actions.DeviceActionCommandRunner
import com.letta.mobile.runtime.actions.DeviceActionExternalTool
import com.letta.mobile.startup.AppStartupActions
import com.letta.mobile.startup.DefaultAppStartupActions
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
        @Provides
        @Singleton
        fun provideWsChatBridge(transport: IChannelTransport): WsChatBridge = WsChatBridge(transport)

        @Provides
        @Singleton
        // Only device actions are this phone's to answer. canvas_* is the Iroh host's: it offers them
        // on every runtime it serves, backed by the relay's log (letta-mobile-aknkw), and ignores
        // any tools an app sends with runtime_start, so a copy here never reached a runtime.
        fun provideAndroidExternalToolRegistry(
            runner: DeviceActionCommandRunner,
        ): ExternalToolRegistry = ExternalToolRegistry.hostTools(listOf(DeviceActionExternalTool(runner)))

        /** One registry per process here, but owned by the graph so it dies with it. */
        @Provides
        @Singleton
        fun provideCanvasSessionRegistry(): com.letta.mobile.data.canvas.CanvasSessionRegistry =
            com.letta.mobile.data.canvas.CanvasSessionRegistry()

        /**
         * Shares canvases through the Iroh host every session graph connects to (attached by
         * SessionChannelTransportFactory). Edits are queued durably in the app's files until the
         * host acknowledges them; without a host every canvas reports itself local-only.
         */
        /**
         * Where the app's canvases keep their images (and any other large things put on a board),
         * by their hash. One store for the boards and the relay, so an image placed on a board is
         * the one sent to the host, and one fetched from the host is the one the board draws.
         */
        @Provides
        @Singleton
        fun provideCanvasAssetStore(
            @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        ): com.letta.mobile.data.storage.AssetStore =
            com.letta.mobile.data.storage.FileAssetStore(java.io.File(context.filesDir, "canvas-assets"))

        @Provides
        @Singleton
        fun provideCanvasRelayClient(
            @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
            opLog: com.letta.mobile.data.canvas.CanvasOpLog,
            documents: com.letta.mobile.data.canvas.CanvasDocumentStore,
            assets: com.letta.mobile.data.storage.AssetStore,
        ): com.letta.mobile.data.canvas.CanvasRelayClient = com.letta.mobile.data.canvas.CanvasRelayClient(
            opLog = opLog,
            delivery = com.letta.mobile.data.canvas.FileCanvasDeliveryStore(java.io.File(context.filesDir, "canvas-delivery.json")),
            topicOf = { id -> documents.relayTopicOf(id) },
            assets = assets,
            // Ops for a board that is not open (the agent drawing while the person is in chat)
            // go straight into the stored canvas (letta-mobile-qygvv.23).
            closedBoard = com.letta.mobile.data.canvas.StoreCanvasClosedBoardApplier(documents, opLog),
        )

        @Provides
        @Singleton
        @Suppress("NoDetachedCoroutineLifecycle") // Process lifetime, like the canvas op log.
        fun provideIrohCanvasRelayClient(
            relay: com.letta.mobile.data.canvas.CanvasRelayClient,
        ): com.letta.mobile.data.transport.iroh.IrohCanvasRelayClient =
            com.letta.mobile.data.transport.iroh.IrohCanvasRelayClient(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                relay,
            )

        @Provides
        @Singleton
        fun provideCanvasSyncTransport(
            relay: com.letta.mobile.data.canvas.CanvasRelayClient,
        ): com.letta.mobile.data.canvas.CanvasSyncTransport = relay

        @Provides
        @Singleton
        fun provideCanvasPresenceTransport(
            relay: com.letta.mobile.data.canvas.CanvasRelayClient,
        ): com.letta.mobile.data.canvas.CanvasPresenceTransport = relay

        // letta-mobile-qfa81 (P4 row 13): approval submission routed over
        // admin_rpc when the active backend is iroh://. Injected into
        // MessageRepository.submitApproval.
        @Provides
        @Singleton
        fun provideIrohAdminRpcApprovalSource(
            transport: IChannelTransport,
            settingsRepository: ISettingsRepository,
        ): IrohAdminRpcApprovalSource = IrohAdminRpcApprovalSource(
            channelTransport = transport,
            settingsRepository = settingsRepository,
        )

        @Provides
        @Singleton
        fun provideIrohAdminRpcBlockSource(
            transport: javax.inject.Provider<IChannelTransport>,
            settingsRepository: ISettingsRepository,
        ): IrohAdminRpcBlockSource = IrohAdminRpcBlockSource(
            channelTransportProvider = transport::get,
            settingsRepository = settingsRepository,
        )

        @Provides
        @Singleton
        fun provideIrohAdminRpcProjectSource(
            transport: IChannelTransport,
            settingsRepository: ISettingsRepository,
        ): IrohAdminRpcProjectSource = IrohAdminRpcProjectSource(
            channelTransport = transport,
            settingsRepository = settingsRepository,
        )

        // letta-mobile-71orq: older-message pagination (scroll up for history)
        // routes over message.list admin_rpc in iroh:// mode.
        @Provides
        @Singleton
        fun provideIrohAdminRpcTimelineTransport(
            transport: IChannelTransport,
            settingsRepository: ISettingsRepository,
        ): IrohAdminRpcTimelineTransport = IrohAdminRpcTimelineTransport(
            channelTransport = transport,
            settingsRepository = settingsRepository,
        )

        @Provides
        @Singleton
        fun provideTimelineExternalTransportWriter(impl: TimelineRepository): TimelineExternalTransportWriter =
            impl.admittedExternalWriter

        @Provides
        @Singleton
        fun provideAndroidCanonicalRuntimeFactory(
            legacy: TimelineRepository,
            authority: com.letta.mobile.data.local.TimelineOwnershipAuthority,
            storage: com.letta.mobile.data.local.TimelineOwnedStorageFactory,
        ) = AndroidCanonicalTimelineRuntimeFactory(legacy, authority, storage)

        @Provides
        @Singleton
        fun provideCanonicalTimelineTransport(
            local: com.letta.mobile.runtime.local.LettaCodeLocalTimelineTransport,
            remote: IrohAdminRpcTimelineTransport,
        ): com.letta.mobile.data.timeline.TimelineTransport =
            // letta-mobile-ckmub.4: on-device conversations read the embedded runtime; every other
            // conversation is an Iroh host's. There is no HTTP timeline any more.
            com.letta.mobile.runtime.local.LocalRoutingTimelineTransport(local = local, remote = remote)

        // letta-mobile-by4vm.88: each repository below forwards to the current session graph
        // (SessionForwarding.kt), so a backend switch retargets it without a rebuild here.
        @Provides @Singleton
        fun provideAgentRepository(sessions: SessionManager): IAgentRepository = sessions.scopedAgentRepository()

        @Provides @Singleton
        fun provideAllConversationsRepository(sessions: SessionManager): IAllConversationsRepository =
            sessions.scopedAllConversationsRepository()

        @Provides @ElementsIntoSet
        fun provideSessionBackendScopedCaches(sessions: SessionManager): Set<BackendScopedCache> =
            sessions.sessionBackendScopedCaches()

        @Provides @Singleton
        fun provideConversationRepository(sessions: SessionManager): IConversationRepository =
            sessions.sessionScoped(IConversationRepository::class) { it.conversationRepository }

        @Provides @Singleton
        fun provideCronRepository(sessions: SessionManager): ICronRepository =
            sessions.sessionScoped(ICronRepository::class) { it.cronRepository }

        @Provides @Singleton
        fun provideSubagentRepository(sessions: SessionManager): ISubagentRepository =
            sessions.sessionScoped(ISubagentRepository::class) { it.subagentRepository }

        @Provides @Singleton
        fun provideSelfTodoRepository(sessions: SessionManager): ISelfTodoRepository =
            sessions.sessionScoped(ISelfTodoRepository::class) { it.selfTodoRepository }

        @Provides @Singleton
        fun provideProjectRepository(sessions: SessionManager): IProjectRepository =
            sessions.sessionScoped(IProjectRepository::class) { it.projectRepository }

        @Provides @Singleton
        fun provideProjectWorkRepository(sessions: SessionManager): IProjectWorkRepository =
            sessions.sessionScoped(IProjectWorkRepository::class) { it.projectWorkRepository }

        @Provides @Singleton
        fun provideToolRepository(sessions: SessionManager): IToolRepository =
            sessions.sessionScoped(IToolRepository::class) { it.toolRepository }

        @Provides @Singleton
        fun provideMcpServerRepository(sessions: SessionManager): IMcpServerRepository =
            sessions.sessionScoped(IMcpServerRepository::class) { it.mcpServerRepository }

        @Provides @Singleton
        fun provideModelRepository(sessions: SessionManager): IModelRepository =
            sessions.sessionScoped(IModelRepository::class) { it.modelRepository }

        @Provides @Singleton
        fun providePassageRepository(sessions: SessionManager): IPassageRepository =
            sessions.sessionScoped(IPassageRepository::class) { it.passageRepository }

        @Provides @Singleton
        fun provideProviderRepository(sessions: SessionManager): IProviderRepository =
            sessions.sessionScoped(IProviderRepository::class) { it.providerRepository }

        @Provides @Singleton
        fun provideRunRepository(sessions: SessionManager): IRunRepository =
            sessions.sessionScoped(IRunRepository::class) { it.runRepository }

        @Provides @Singleton
        fun provideScheduleRepository(sessions: SessionManager): IScheduleRepository =
            sessions.sessionScoped(IScheduleRepository::class) { it.scheduleRepository }

        @Provides @Singleton
        fun provideStepRepository(sessions: SessionManager): IStepRepository =
            sessions.sessionScoped(IStepRepository::class) { it.stepRepository }

        /** letta-mobile-ckmub.4: the backend seam; the App Server is the only backend for now. */
        @Provides
        @Singleton
        fun provideSessionRemoteSources(): com.letta.mobile.data.session.SessionRemoteSources =
            com.letta.mobile.data.session.SessionRemoteSources.None

        @Provides
        @Singleton
        fun provideTimelineRepository(
            timelineTransport: com.letta.mobile.data.timeline.TimelineTransport,
            pendingLocalStore: PendingLocalStore,
            conversationCursorStore: ConversationCursorStore,
            confirmedTimelineStore: com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore,
            settingsRepository: ISettingsRepository,
            @ProcessScope processScope: CoroutineScope,
        ): TimelineRepository {
            return TimelineRepository(
                // local-conv-* hydrates from the on-device letta.js transcript
                // (letta-mobile-czomn); everything else uses the active remote route.
                timelineTransport = timelineTransport,
                pendingLocalStore = pendingLocalStore,
                conversationCursorStore = conversationCursorStore,
                confirmedTimelineStore = confirmedTimelineStore,
                backendIdProvider = { settingsRepository.activeConfig.value?.id ?: UNCONFIGURED_BACKEND_KEY },
                repositoryScope = processScope,
            )
        }
    }

    @Binds
    @Singleton
    abstract fun bindSelectedChatRuntimeProvider(
        impl: ProductionSelectedChatRuntimeProvider,
    ): com.letta.mobile.feature.chat.coordination.SelectedChatRuntimeProvider

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




    @Binds
    @Singleton
    abstract fun bindSlashCommandRepository(impl: SlashCommandRepository): ISlashCommandRepository





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
    abstract fun bindVibesyncEventStreamRepository(
        impl: SessionScopedVibesyncEventStreamRepository,
    ): IVibesyncEventStreamRepository


    @Binds
    @Singleton
    abstract fun bindBugReportRepository(impl: BugReportRepository): IBugReportRepository









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
    @IntoSet
    @Singleton
    abstract fun bindTimelineBackendScopedCache(impl: TimelineRepository): BackendScopedCache

    /** letta-mobile-by4vm.17: unsent messages are backend-owned too; drop them on a switch. */
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPendingLocalBackendScopedCache(
        impl: com.letta.mobile.data.local.RoomPendingLocalStore,
    ): BackendScopedCache

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
