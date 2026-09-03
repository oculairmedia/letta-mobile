package com.letta.mobile.desktop.data

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatSessionGraph
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ActiveConfigSettingsRepository
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IArchiveRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.api.IGroupRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
import com.letta.mobile.data.repository.api.IJobRepository
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
import com.letta.mobile.data.repository.iroh.buildIrohAdminReadRepositories
import com.letta.mobile.data.session.DEFAULT_REMOTE_LETTA_URL
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DesktopRepositoryUnavailableException(
    contractName: String,
    operationName: String,
) : UnsupportedOperationException(
    "Desktop repository contract $contractName is not bound for operation $operationName. " +
        "Install a JVM desktop implementation before invoking this API.",
)

class DesktopSessionGraph internal constructor(
    override val id: Long,
    override val backendDescriptor: BackendDescriptor,
    override val localRuntimeBackend: LettaBackend?,
    override val agentRepository: IAgentRepository,
    override val blockRepository: IAgentBlockRepository,
    override val channelTransport: IChannelTransport,
    override val conversationRepository: IConversationRepository,
    override val cronRepository: ICronRepository,
    override val archiveRepository: IArchiveRepository,
    override val folderRepository: IFolderRepository,
    override val groupRepository: IGroupRepository,
    override val identityRepository: IIdentityRepository,
    override val mcpServerRepository: IMcpServerRepository,
    override val modelRepository: IModelRepository,
    override val passageRepository: IPassageRepository,
    override val projectRepository: IProjectRepository,
    override val projectWorkRepository: IProjectWorkRepository,
    override val runRepository: IRunRepository,
    override val jobRepository: IJobRepository,
    override val providerRepository: IProviderRepository,
    override val scheduleRepository: IScheduleRepository,
    override val selfTodoRepository: ISelfTodoRepository,
    override val stepRepository: IStepRepository,
    override val subagentRepository: ISubagentRepository,
    override val toolRepository: IToolRepository,
    override val vibesyncEventStreamRepository: IVibesyncEventStreamRepository,
    private val closeables: List<AutoCloseable> = emptyList(),
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
    private val repositoryAdaptersFactory: (LettaConfig?, IChannelTransport) -> DesktopRepositoryAdapters =
        { config, transport ->
            DesktopRepositoryAdapters(
                config = config,
                irohAgentDirectoryProvider = irohAgentDirectoryProvider,
                channelTransport = transport,
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
            archiveRepository = adapters.archiveRepository,
            folderRepository = adapters.folderRepository,
            groupRepository = adapters.groupRepository,
            identityRepository = adapters.identityRepository,
            mcpServerRepository = adapters.mcpServerRepository,
            modelRepository = adapters.modelRepository,
            passageRepository = adapters.passageRepository,
            projectRepository = adapters.projectRepository,
            projectWorkRepository = adapters.projectWorkRepository,
            runRepository = adapters.runRepository,
            jobRepository = adapters.jobRepository,
            providerRepository = adapters.providerRepository,
            scheduleRepository = adapters.scheduleRepository,
            selfTodoRepository = adapters.selfTodoRepository,
            stepRepository = adapters.stepRepository,
            subagentRepository = adapters.subagentRepository,
            toolRepository = adapters.toolRepository,
            vibesyncEventStreamRepository = adapters.vibesyncEventStreamRepository,
            closeables = adapters.closeables,
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
): DesktopChatSessionGraphFactory =
    DesktopChatSessionGraphFactory(
        repositoryGraphFactory = repositoryGraphFactory,
        gatewayFactory = { createDefaultDesktopChatGateway(configProvider() ?: defaultDesktopLettaConfig()) },
    )

@Suppress("NoDetachedCoroutineLifecycle")
class DesktopRepositoryAdapters(
    config: LettaConfig? = null,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
    channelTransport: IChannelTransport = NoOpChannelTransport(),
) {
    // letta-mobile-9v9nu: mode is authoritative — LOCAL never binds remote Iroh
    // even if serverUrl still carries a leftover iroh:// ticket.
    private val binding = config.sessionBackendBinding(
        forceIroh = IrohChannelTransport.shouldUseIroh(config?.serverUrl),
    )
    private val localMode = binding == SessionBackendBinding.LocalRuntime
    private val irohMode = binding == SessionBackendBinding.Iroh
    private val localRepositories = if (localMode) {
        buildDesktopLocalRepositories()
    } else {
        null
    }
    private val adminRepositories = buildHttpAdminRepositories(config, irohMode)
    private val irohRepositories = buildIrohRepositories(irohMode, irohAgentDirectoryProvider)
    private val irohAdminReads = if (irohMode) {
        buildIrohAdminReadRepositories(
            channelTransport = channelTransport,
            settingsRepository = ActiveConfigSettingsRepository(config),
        )
    } else {
        null
    }
    private val transportBoundJob = SupervisorJob()
    private val transportBoundScope = CoroutineScope(transportBoundJob + Dispatchers.Default)
    private val boundCronRepository = CronRepository(channelTransport, transportBoundScope)
    private val boundSelfTodoRepository = SelfTodoRepository(channelTransport, transportBoundScope)
    private val boundSubagentRepository = SubagentRepository(
        transport = channelTransport,
        scope = transportBoundScope,
        includeAll = true,
    )

    val closeables: List<AutoCloseable> = listOfNotNull(
        adminRepositories,
        AutoCloseable {
            boundSubagentRepository.close()
            transportBoundJob.cancel()
        },
    )

    val agentRepository: IAgentRepository = localRepositories?.agentRepository
        ?: selectIrohOrHttp(irohRepositories?.agentRepository, adminRepositories)
    val blockRepository: IAgentBlockRepository = localRepositories?.blockRepository
        ?: selectIrohOrHttp(irohRepositories?.blockRepository, adminRepositories)
    val archiveRepository: IArchiveRepository =
        irohAdminReads?.archiveRepository ?: unavailableRepository()
    val conversationRepository: IConversationRepository = unavailableRepository()
    val cronRepository: ICronRepository = boundCronRepository
    val folderRepository: IFolderRepository =
        irohAdminReads?.folderRepository ?: unavailableRepository()
    val groupRepository: IGroupRepository =
        irohAdminReads?.groupRepository ?: unavailableRepository()
    val identityRepository: IIdentityRepository =
        irohAdminReads?.identityRepository ?: unavailableRepository()
    val jobRepository: IJobRepository =
        irohAdminReads?.jobRepository ?: unavailableRepository()
    val mcpServerRepository: IMcpServerRepository =
        irohAdminReads?.mcpServerRepository ?: unavailableRepository()
    val modelRepository: IModelRepository =
        irohAdminReads?.modelRepository ?: unavailableRepository()
    val passageRepository: IPassageRepository =
        irohAdminReads?.passageRepository ?: unavailableRepository()
    val projectRepository: IProjectRepository = unavailableRepository()
    val projectWorkRepository: IProjectWorkRepository = unavailableRepository()
    val providerRepository: IProviderRepository =
        irohAdminReads?.providerRepository ?: unavailableRepository()
    val runRepository: IRunRepository =
        irohAdminReads?.runRepository ?: unavailableRepository()
    val scheduleRepository: IScheduleRepository = selectIrohOrHttp(
        irohRepositories?.scheduleRepository,
        adminRepositories,
    )
    val selfTodoRepository: ISelfTodoRepository = boundSelfTodoRepository
    val stepRepository: IStepRepository = unavailableRepository()
    val subagentRepository: ISubagentRepository = boundSubagentRepository
    val toolRepository: IToolRepository = selectIrohOrHttp(
        irohRepositories?.toolRepository,
        adminRepositories,
    )
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository = unavailableRepository()
}

fun desktopRemoteLettaDescriptor(config: LettaConfig?): BackendDescriptor =
    remoteLettaBackendDescriptor(
        config = config,
        idPrefix = DESKTOP_REMOTE_LETTA_ID_PREFIX,
        defaultLabel = DEFAULT_REMOTE_LETTA_URL,
    )
