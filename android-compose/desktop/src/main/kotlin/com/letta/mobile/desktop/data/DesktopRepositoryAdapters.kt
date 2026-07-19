package com.letta.mobile.desktop.data

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatSessionGraph
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IAgentRepository
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
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphFactory
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.LettaBackend
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val DEFAULT_REMOTE_LETTA_URL = "https://api.letta.com"

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
    private val repositoryAdaptersFactory: (LettaConfig?) -> DesktopRepositoryAdapters = ::DesktopRepositoryAdapters,
) : SessionRepositoryGraphFactory<DesktopSessionGraph> {
    private val nextId = AtomicLong(0L)

    override fun create(): DesktopSessionGraph {
        val config = configProvider()
        val adapters = repositoryAdaptersFactory(config)
        return DesktopSessionGraph(
            id = nextId.incrementAndGet(),
            backendDescriptor = desktopRemoteLettaDescriptor(config),
            localRuntimeBackend = null,
            agentRepository = adapters.agentRepository,
            channelTransport = channelTransportFactory(),
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
    private val factory: SessionRepositoryGraphFactory<DesktopSessionGraph>,
) : SessionRepositoryGraphProvider<DesktopSessionGraph> {
    private val currentGraphFlow = MutableStateFlow(factory.create())
    override val currentGraph: StateFlow<DesktopSessionGraph> = currentGraphFlow
    private val sessionErrorFlow = MutableStateFlow<Throwable?>(null)
    override val sessionError: StateFlow<Throwable?> = sessionErrorFlow

    override val current: DesktopSessionGraph
        get() = currentGraph.value

    @Synchronized
    override fun rebuild(): DesktopSessionGraph {
        val previous = currentGraphFlow.value
        return try {
            val next = factory.create()
            currentGraphFlow.value = next
            previous.close()
            sessionErrorFlow.value = null
            next
        } catch (t: Throwable) {
            sessionErrorFlow.value = t
            throw t
        }
    }

    override suspend fun <T> withCurrentSession(block: suspend (DesktopSessionGraph) -> T): T {
        val graph = current
        val result = block(graph)
        if (current !== graph) {
            throw CancellationException("Desktop session switched during operation")
        }
        return result
    }
}

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

class DesktopRepositoryAdapters(
    config: LettaConfig? = null,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory? = { null },
) {
    private val irohMode = IrohChannelTransport.isIrohUrl(config?.serverUrl)
    private val adminRepositories = buildHttpAdminRepositories(config, irohMode)
    private val irohRepositories = buildIrohRepositories(irohMode, irohAgentDirectoryProvider)

    val closeables: List<AutoCloseable> = listOfNotNull(adminRepositories)

    val agentRepository: IAgentRepository = selectIrohOrHttp(
        irohRepositories?.agentRepository,
        adminRepositories,
    )
    val archiveRepository: IArchiveRepository = unavailableRepository()
    val conversationRepository: IConversationRepository = unavailableRepository()
    val cronRepository: ICronRepository = unavailableRepository()
    val folderRepository: IFolderRepository = unavailableRepository()
    val groupRepository: IGroupRepository = unavailableRepository()
    val identityRepository: IIdentityRepository = unavailableRepository()
    val jobRepository: IJobRepository = unavailableRepository()
    val mcpServerRepository: IMcpServerRepository = unavailableRepository()
    val modelRepository: IModelRepository = unavailableRepository()
    val passageRepository: IPassageRepository = unavailableRepository()
    val projectRepository: IProjectRepository = unavailableRepository()
    val projectWorkRepository: IProjectWorkRepository = unavailableRepository()
    val providerRepository: IProviderRepository = unavailableRepository()
    val runRepository: IRunRepository = unavailableRepository()
    val scheduleRepository: IScheduleRepository = selectIrohOrHttp(
        irohRepositories?.scheduleRepository,
        adminRepositories,
    )
    val selfTodoRepository: ISelfTodoRepository = unavailableRepository()
    val stepRepository: IStepRepository = unavailableRepository()
    val subagentRepository: ISubagentRepository = unavailableRepository()
    val toolRepository: IToolRepository = selectIrohOrHttp(
        irohRepositories?.toolRepository,
        adminRepositories,
    )
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository = unavailableRepository()
}

fun desktopRemoteLettaDescriptor(config: LettaConfig?): BackendDescriptor {
    val backendKey = config?.id?.takeIf { it.isNotBlank() } ?: "default"
    val label = config?.serverUrl?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_REMOTE_LETTA_URL
    return BackendDescriptor(
        backendId = BackendId("desktop-remote-letta:$backendKey"),
        runtimeId = RuntimeId("desktop-remote-letta:$backendKey"),
        kind = BackendKind.RemoteLetta,
        label = label,
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsToolEvents = true,
            supportsToolExecution = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )
}

