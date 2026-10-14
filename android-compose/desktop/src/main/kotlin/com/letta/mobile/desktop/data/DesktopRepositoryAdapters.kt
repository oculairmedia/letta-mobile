package com.letta.mobile.desktop.data

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.unavailable
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatSessionGraph
import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.contextTokenReadingsOf
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ActiveConfigSettingsRepository
import com.letta.mobile.data.repository.CachedProjectRepository
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAgentRepository
import com.letta.mobile.data.repository.iroh.buildIrohAdminReadRepositories
import com.letta.mobile.data.session.DEFAULT_REMOTE_BACKEND_LABEL
import com.letta.mobile.data.session.DefaultSessionRepositoryGraphProvider
import com.letta.mobile.data.session.DESKTOP_REMOTE_LETTA_ID_PREFIX
import com.letta.mobile.data.session.SessionBackendBinding
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphFactory
import com.letta.mobile.data.session.remoteLettaBackendDescriptor
import com.letta.mobile.data.session.sessionBackendBinding
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.LettaBackend
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.desktop.chat.defaultDesktopAppServerGatewayFactory
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DesktopSessionGraph internal constructor(
    override val id: Long,
    override val backendDescriptor: BackendDescriptor,
    override val localRuntimeBackend: LettaBackend?,
    override val agentRepository: IAgentRepository,
    override val blockRepository: IAgentBlockRepository,
    override val channelTransport: IChannelTransport,
    override val conversationRepository: IConversationRepository,
    override val cronRepository: ICronRepository,
    override val mcpServerRepository: IMcpServerRepository,
    override val modelRepository: IModelRepository,
    override val passageRepository: IPassageRepository,
    override val projectRepository: IProjectRepository,
    override val projectWorkRepository: IProjectWorkRepository,
    override val runRepository: IRunRepository,
    override val providerRepository: IProviderRepository,
    override val scheduleRepository: IScheduleRepository,
    override val selfTodoRepository: ISelfTodoRepository,
    override val stepRepository: IStepRepository,
    override val subagentRepository: ISubagentRepository,
    override val toolRepository: IToolRepository,
    override val vibesyncEventStreamRepository: IVibesyncEventStreamRepository,
    override val capabilities: SessionCapabilities,
    private val closeables: List<AutoCloseable> = emptyList(),
    /** letta-mobile-r2zo8: each conversation's latest context total; inert unless a transport feeds it. */
    val contextTokenReadings: ContextTokenReadings = ContextTokenReadings(),
) : SessionRepositoryGraph {
    private val closedRef = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closedRef.get()

    override fun close() {
        if (closedRef.compareAndSet(false, true)) {
            closeables.forEach { closeable ->
                runCatching { closeable.close() }
            }
        }
    }
}

class DesktopSessionGraphFactory(
    private val configProvider: () -> LettaConfig? = { null },
    private val channelTransportFactory: () -> IChannelTransport = ::NoOpChannelTransport,
    private val irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
    /** letta-mobile-wdm6i: where the context chip's last readings survive a restart. */
    private val contextReadingSnapshots: ContextReadingSnapshots? = null,
    private val repositoryAdaptersFactory: (LettaConfig?, IChannelTransport) -> DesktopRepositoryAdapters =
        { config, transport ->
            DesktopRepositoryAdapters(
                config = config,
                irohAgentDirectoryProvider = irohAgentDirectoryProvider,
                channelTransport = transport,
                contextReadingSnapshots = contextReadingSnapshots,
            )
        },
) : SessionRepositoryGraphFactory<DesktopSessionGraph> {
    private val nextId = AtomicLong(0L)

    override fun create(): DesktopSessionGraph {
        val config = configProvider()
        val channelTransport = channelTransportFactory()
        val adapters = repositoryAdaptersFactory(config, channelTransport)
        return DesktopSessionGraph(
            id = nextId.incrementAndGet(),
            backendDescriptor = desktopRemoteLettaDescriptor(config),
            localRuntimeBackend = null,
            agentRepository = adapters.agentRepository,
            blockRepository = adapters.blockRepository,
            channelTransport = channelTransport,
            conversationRepository = adapters.conversationRepository,
            cronRepository = adapters.cronRepository,
            mcpServerRepository = adapters.mcpServerRepository,
            modelRepository = adapters.modelRepository,
            passageRepository = adapters.passageRepository,
            projectRepository = adapters.projectRepository,
            projectWorkRepository = adapters.projectWorkRepository,
            runRepository = adapters.runRepository,
            providerRepository = adapters.providerRepository,
            scheduleRepository = adapters.scheduleRepository,
            selfTodoRepository = adapters.selfTodoRepository,
            stepRepository = adapters.stepRepository,
            subagentRepository = adapters.subagentRepository,
            toolRepository = adapters.toolRepository,
            vibesyncEventStreamRepository = adapters.vibesyncEventStreamRepository,
            capabilities = adapters.capabilities,
            closeables = adapters.closeables,
            contextTokenReadings = adapters.contextTokenReadings,
        )
    }
}

class DesktopSessionGraphProvider(
    factory: SessionRepositoryGraphFactory<DesktopSessionGraph>,
) : DefaultSessionRepositoryGraphProvider<DesktopSessionGraph>(
    factory = factory,
    sessionSwitchMessage = "Desktop session switched during operation",
)

class DesktopChatSessionGraph internal constructor(
    override val repositories: DesktopSessionGraph,
    override val gateway: ChatGateway,
) : ChatSessionGraph<DesktopSessionGraph> {
    override fun close() {
        repositories.close()
        (gateway as? AutoCloseable)?.close()
    }
}

class DesktopChatSessionGraphFactory(
    private val repositoryGraphFactory: SessionRepositoryGraphFactory<DesktopSessionGraph>,
    private val gatewayFactory: suspend () -> ChatGateway,
) {
    suspend fun create(): DesktopChatSessionGraph =
        DesktopChatSessionGraph(
            repositories = repositoryGraphFactory.create(),
            gateway = gatewayFactory(),
        )
}

fun defaultDesktopChatSessionGraphFactory(
    configProvider: () -> LettaConfig? = { null },
    repositoryGraphFactory: SessionRepositoryGraphFactory<DesktopSessionGraph> =
        DesktopSessionGraphFactory(configProvider = configProvider),
    permissionModeSettings: com.letta.mobile.data.runtime.PermissionModeSettings? = null,
): DesktopChatSessionGraphFactory =
    DesktopChatSessionGraphFactory(
        repositoryGraphFactory = repositoryGraphFactory,
        gatewayFactory = {
            createDefaultDesktopChatGateway(
                configProvider() ?: defaultDesktopLettaConfig(),
                appServerGatewayFactory = defaultDesktopAppServerGatewayFactory(permissionModeSettings),
            )
        },
    )

@Suppress("NoDetachedCoroutineLifecycle")
class DesktopRepositoryAdapters(
    config: LettaConfig? = null,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
    channelTransport: IChannelTransport = NoOpChannelTransport(),
    contextReadingSnapshots: ContextReadingSnapshots? = null,
) {
    // letta-mobile-9v9nu: mode is authoritative — LOCAL never binds remote Iroh
    // even if serverUrl still carries a leftover iroh:// ticket.
    private val binding = config.sessionBackendBinding(
        forceIroh = IrohChannelTransport.shouldUseIroh(config?.serverUrl),
    )
    private val irohMode = binding == SessionBackendBinding.Iroh

    /**
     * letta-mobile-by4vm.13: what this session serves — the desktop row of the
     * [com.letta.mobile.data.backend.SessionRouteMatrix]. A remote host with no address is no backend.
     * The bindings below decide independently; the desktop matrix test holds the two together.
     */
    val capabilities: SessionCapabilities = SessionCapabilities.forSession(
        platform = SessionPlatform.DESKTOP,
        kind = desktopBackendKind(binding, config),
        hostCapabilities = { channelTransport.hostCapabilities },
    )

    /** True when this session's admin surfaces ride an Iroh host's relays (skills, slash commands). */
    internal val servesIrohRelays: Boolean get() = irohMode

    // letta-mobile-ckmub.4: every non-Iroh backend is an App Server this window dials directly (the
    // bundled runtime or a remote ws:// host); its agents and memory are read over that session.
    private val appServerRepositories = when {
        binding == SessionBackendBinding.LocalRuntime ->
            buildDesktopAppServerRepositories(DesktopLocalAppServerClientRegistry.shared)
        !irohMode && !config?.serverUrl.isNullOrBlank() ->
            buildDesktopAppServerRepositories(DesktopLocalAppServerClientRegistry.direct)
        // No backend configured yet: nothing to read from.
        else -> null
    }
    private val transportBoundJob = SupervisorJob()
    private val transportBoundScope = CoroutineScope(transportBoundJob + Dispatchers.Default)
    private val irohRepositories = buildIrohRepositories(irohMode, irohAgentDirectoryProvider)

    // The Iroh agent roster follows Meridian's agent_updated pushes (and refreshes on reconnect) on
    // the transport-bound scope, so changes made on another device show without a restart.
    private val irohAgentRepository = if (irohMode) {
        IrohAgentRepository(irohAgentDirectoryProvider, channelTransport, transportBoundScope)
    } else {
        null
    }
    private val irohSettings = ActiveConfigSettingsRepository(config)
    private val irohAdminReads = if (irohMode) {
        buildIrohAdminReadRepositories(channelTransport = channelTransport, settingsRepository = irohSettings)
    } else {
        null
    }

    // letta-mobile-ckmub.2.6: projects ride the Iroh host's `project.*` relay; no other backend has them.
    private val irohProjectRepository = if (irohMode) {
        CachedProjectRepository(
            remote = RemoteRoute.Absent(SessionRoute.PROJECTS),
            irohProjectSource = IrohAdminRpcProjectSource(channelTransport, irohSettings),
        )
    } else {
        null
    }
    private val boundCronRepository = CronRepository(channelTransport, transportBoundScope)

    /** Fed by the same transport-bound scope, so it retires with the transport generation. */
    val contextTokenReadings: ContextTokenReadings =
        contextTokenReadingsOf(channelTransport, transportBoundScope, contextReadingSnapshots)
    private val boundSelfTodoRepository = SelfTodoRepository(channelTransport, transportBoundScope)
    private val boundSubagentRepository = SubagentRepository(
        transport = channelTransport,
        scope = transportBoundScope,
        includeAll = true,
    )

    val closeables: List<AutoCloseable> = listOf(
        AutoCloseable {
            boundSubagentRepository.close()
            transportBoundJob.cancel()
        },
    )

    val agentRepository: IAgentRepository = appServerRepositories?.agentRepository
        ?: irohAgentRepository
        ?: unavailable(SessionRoute.AGENTS)
    val blockRepository: IAgentBlockRepository = appServerRepositories?.blockRepository
        ?: irohRepositories?.blockRepository
        ?: unavailable(SessionRoute.BLOCKS)
    val conversationRepository: IConversationRepository = unavailable(SessionRoute.CONVERSATIONS)
    val cronRepository: ICronRepository = boundCronRepository
    val mcpServerRepository: IMcpServerRepository =
        irohAdminReads?.mcpServerRepository ?: unavailable(SessionRoute.MCP_SERVERS)
    val modelRepository: IModelRepository =
        irohAdminReads?.modelRepository ?: unavailable(SessionRoute.MODELS)
    val passageRepository: IPassageRepository =
        irohAdminReads?.passageRepository ?: unavailable(SessionRoute.PASSAGES)
    val projectRepository: IProjectRepository = irohProjectRepository ?: unavailable(SessionRoute.PROJECTS)
    val projectWorkRepository: IProjectWorkRepository = unavailable(SessionRoute.PROJECT_WORK)
    val providerRepository: IProviderRepository =
        irohAdminReads?.providerRepository ?: unavailable(SessionRoute.PROVIDERS)
    val runRepository: IRunRepository =
        irohAdminReads?.runRepository ?: unavailable(SessionRoute.RUNS)
    val scheduleRepository: IScheduleRepository = irohRepositories?.scheduleRepository ?: unavailable(SessionRoute.SCHEDULES)
    val selfTodoRepository: ISelfTodoRepository = boundSelfTodoRepository
    val stepRepository: IStepRepository = unavailable(SessionRoute.STEPS)
    val subagentRepository: ISubagentRepository = boundSubagentRepository
    val toolRepository: IToolRepository = irohRepositories?.toolRepository ?: unavailable(SessionRoute.TOOLS)
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository = unavailable(SessionRoute.VIBESYNC)
}

/** The backend kind a desktop session binds; a remote host with no address is no backend at all. */
private fun desktopBackendKind(binding: SessionBackendBinding, config: LettaConfig?): BackendKind? = when (binding) {
    SessionBackendBinding.LocalRuntime -> BackendKind.LOCAL_RUNTIME
    SessionBackendBinding.Iroh -> BackendKind.IROH
    SessionBackendBinding.RemoteHttp -> BackendKind.REST.takeUnless { config?.serverUrl.isNullOrBlank() }
}

fun desktopRemoteLettaDescriptor(config: LettaConfig?): BackendDescriptor =
    remoteLettaBackendDescriptor(
        config = config,
        idPrefix = DESKTOP_REMOTE_LETTA_ID_PREFIX,
        defaultLabel = DEFAULT_REMOTE_BACKEND_LABEL,
    )
