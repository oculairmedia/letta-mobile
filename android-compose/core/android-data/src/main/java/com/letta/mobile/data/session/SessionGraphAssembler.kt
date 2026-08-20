package com.letta.mobile.data.session

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.api.ProjectWorkApi
import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.GroupRepository
import com.letta.mobile.data.repository.IdentityRepository
import com.letta.mobile.data.repository.IrohAdminRpcClient
import com.letta.mobile.data.repository.IrohAdminRpcIdentitySource
import com.letta.mobile.data.repository.IrohAdminRpcJobSource
import com.letta.mobile.data.repository.IrohAdminRpcMcpSource
import com.letta.mobile.data.repository.IrohAdminRpcModelSource
import com.letta.mobile.data.repository.IrohAdminRpcPassageSource
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.IrohAdminRpcProviderSource
import com.letta.mobile.data.repository.IrohAdminRpcRunSource
import com.letta.mobile.data.repository.IrohAdminRpcToolSource
import com.letta.mobile.data.repository.JobRepository
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.PassageRepository
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.data.repository.ProjectWorkRepository
import com.letta.mobile.data.repository.ProviderRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.repository.IrohAdminRpcConversationListSource
import com.letta.mobile.data.repository.IrohAdminRpcFolderSource
import com.letta.mobile.data.repository.IrohAdminRpcGroupSource
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt-owned assembler that wires session-scoped repositories onto a
 * [SessionGraph]. App consumers receive the same repositories through
 * SessionScoped* Hilt bindings; this class only builds each graph generation.
 */
@Singleton
// letta-mobile-g2ff0: agentDao and conversationDao are dagger.Lazy<T> so Room init
// happens lazily on the first dao.get() (inside clearCachesForNewSession /
// repositoryScope.launch), not on the main thread during Hilt graph resolution.
class SessionGraphAssembler @Inject constructor(
    private val agentApi: AgentApi,
    private val agentDao: Lazy<AgentDao>,
    private val conversationApi: ConversationApi,
    private val conversationDao: Lazy<ConversationDao>,
    private val archiveApi: ArchiveApi,
    private val folderApi: FolderApi,
    private val groupApi: GroupApi,
    private val identityApi: IdentityApi,
    private val lettaApiClient: LettaApiClient,
    private val mcpServerApi: McpServerApi,
    private val modelApi: ModelApi,
    private val passageApi: PassageApi,
    private val projectApi: ProjectApi,
    private val projectWorkApi: ProjectWorkApi,
    private val runApi: RunApi,
    private val jobApi: JobApi,
    private val providerApi: ProviderApi,
    private val scheduleApi: ScheduleApi,
    private val stepApi: StepApi,
    private val toolApi: ToolApi,
    private val blockRepository: BlockRepository? = null,
    private val localConversationSource: LocalRuntimeConversationSource? = null,
    private val localAgentSource: LocalRuntimeAgentSource? = null,
    private val localModelSource: LocalRuntimeModelSource? = null,
) {
    suspend fun clearCachesForNewSession() {
        agentDao.get().deleteAll()
        conversationDao.get().deleteAll()
        conversationDao.get().deleteAllRefreshStates()
    }

    fun assemble(request: SessionGraphAssembleRequest): SessionGraph {
        val agentRepository = createAgentRepository(request)
        val conversationRepos = createConversationRepos(request, agentRepository)
        val adminRepositories = createAdminRepositories(request)
        val binding = request.activeConfig.sessionBackendBinding(
            forceIroh = IrohChannelTransport.shouldUseIroh(request.activeConfig?.serverUrl),
        )
        val useIroh = binding.bindsIroh()
        return SessionGraph(
            id = request.graphId,
            backendDescriptor = request.localRuntimeBackend?.descriptor
                ?: remoteLettaDescriptor(request.activeConfig),
            localRuntimeBackend = request.localRuntimeBackend,
            scope = request.scope,
            agentRepository = agentRepository,
            blockRepository = blockRepository,
            allConversationsRepository = conversationRepos.allConversations,
            channelTransport = request.channelTransport,
            conversationRepository = conversationRepos.conversation,
            cronRepository = CronRepository(
                transport = request.channelTransport,
                scope = request.scope,
            ),
            archiveRepository = adminRepositories.archive,
            folderRepository = adminRepositories.folder,
            groupRepository = adminRepositories.group,
            identityRepository = adminRepositories.identity,
            mcpServerRepository = adminRepositories.mcpServer,
            modelRepository = adminRepositories.model,
            passageRepository = adminRepositories.passage,
            projectRepository = adminRepositories.project,
            projectWorkRepository = ProjectWorkRepository(projectWorkApi),
            runRepository = adminRepositories.run,
            jobRepository = adminRepositories.job,
            providerRepository = adminRepositories.provider,
            scheduleRepository = createScheduleRepository(useIroh, request.channelTransport),
            selfTodoRepository = SelfTodoRepository(
                transport = request.channelTransport,
                scope = request.scope,
            ),
            stepRepository = StepRepository(stepApi),
            subagentRepository = SubagentRepository(
                transport = request.channelTransport,
                scope = request.scope,
            ),
            toolRepository = adminRepositories.tool,
            vibesyncEventStreamRepository = VibesyncEventStreamRepository(
                apiClient = lettaApiClient,
                scope = request.scope,
            ),
        )
    }

    private fun createAgentRepository(request: SessionGraphAssembleRequest): AgentRepository =
        AgentRepository(
            agentApi = agentApi,
            agentDao = agentDao,
            repositoryScope = request.scope,
            localAgentSource = localAgentSource,
            settingsRepository = request.settingsRepository,
            transport = request.channelTransport,
        )

    private fun createConversationRepos(
        request: SessionGraphAssembleRequest,
        agentRepository: AgentRepository,
    ): ConversationRepos {
        val irohConversationListSource = request.settingsRepository?.let {
            IrohAdminRpcConversationListSource(
                channelTransport = request.channelTransport,
                settingsRepository = it,
            )
        }
        return ConversationRepos(
            allConversations = AllConversationsRepository(
                conversationApi = conversationApi,
                conversationDao = conversationDao,
                repositoryScope = request.scope,
                localConversationSource = localConversationSource,
                settingsRepository = request.settingsRepository,
                irohConversationListSource = irohConversationListSource,
            ),
            conversation = ConversationRepository(
                conversationApi = conversationApi,
                agentRepository = agentRepository,
                conversationDao = conversationDao,
                repositoryScope = request.scope,
                localConversationSource = localConversationSource,
                settingsRepository = request.settingsRepository,
                irohConversationListSource = irohConversationListSource,
            ),
        )
    }

    private fun createAdminRepositories(request: SessionGraphAssembleRequest): AdminRepositories {
        val catalog = createCatalogAdminRepositories(request)
        val ops = createOpsAdminRepositories(request)
        return AdminRepositories(
            archive = catalog.archive,
            folder = catalog.folder,
            group = catalog.group,
            identity = catalog.identity,
            mcpServer = catalog.mcpServer,
            model = catalog.model,
            passage = ops.passage,
            project = catalog.project,
            run = ops.run,
            job = ops.job,
            provider = ops.provider,
            tool = ops.tool,
        )
    }

    private fun createCatalogAdminRepositories(request: SessionGraphAssembleRequest): CatalogAdminRepositories {
        val transport = request.channelTransport
        val settings = request.settingsRepository
        return CatalogAdminRepositories(
            archive = ArchiveRepository(
                archiveApi = archiveApi,
                irohAdminRpcClient = settings?.let {
                    IrohAdminRpcClient(channelTransport = transport, settingsRepository = it)
                },
            ),
            folder = FolderRepository(
                folderApi = folderApi,
                irohFolderSource = settings?.let {
                    IrohAdminRpcFolderSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            group = GroupRepository(
                groupApi = groupApi,
                irohGroupSource = settings?.let {
                    IrohAdminRpcGroupSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            identity = IdentityRepository(
                identityApi = identityApi,
                irohIdentitySource = settings?.let {
                    IrohAdminRpcIdentitySource(channelTransport = transport, settingsRepository = it)
                },
            ),
            mcpServer = McpServerRepository(
                mcpServerApi = mcpServerApi,
                irohMcpSource = settings?.let {
                    IrohAdminRpcMcpSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            model = ModelRepository(
                modelApi = modelApi,
                localModelSource = localModelSource,
                settingsRepository = settings,
                irohModelSource = settings?.let {
                    IrohAdminRpcModelSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            project = ProjectRepository(
                projectApi = projectApi,
                irohProjectSource = settings?.let {
                    IrohAdminRpcProjectSource(channelTransport = transport, settingsRepository = it)
                },
            ),
        )
    }

    private fun createOpsAdminRepositories(request: SessionGraphAssembleRequest): OpsAdminRepositories {
        val transport = request.channelTransport
        val settings = request.settingsRepository
        return OpsAdminRepositories(
            passage = PassageRepository(
                passageApi = passageApi,
                irohPassageSource = settings?.let {
                    IrohAdminRpcPassageSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            run = RunRepository(
                runApi = runApi,
                irohRunSource = settings?.let {
                    IrohAdminRpcRunSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            job = JobRepository(
                jobApi = jobApi,
                irohJobSource = settings?.let {
                    IrohAdminRpcJobSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            provider = ProviderRepository(
                providerApi = providerApi,
                irohProviderSource = settings?.let {
                    IrohAdminRpcProviderSource(channelTransport = transport, settingsRepository = it)
                },
            ),
            tool = ToolRepository(
                toolApi = toolApi,
                irohToolSource = settings?.let {
                    IrohAdminRpcToolSource(channelTransport = transport, settingsRepository = it)
                },
            ),
        )
    }

    private fun createScheduleRepository(
        useIroh: Boolean,
        channelTransport: IChannelTransport,
    ) = if (useIroh) {
        val directory = IrohAdminRpcAgentDirectory(channelTransport)
        IrohScheduleRepository { directory }
    } else {
        ScheduleRepository(scheduleApi)
    }

    fun remoteLettaDescriptor(config: LettaConfig?): BackendDescriptor =
        remoteLettaBackendDescriptor(config, ANDROID_REMOTE_LETTA_ID_PREFIX)

    private data class ConversationRepos(
        val allConversations: AllConversationsRepository,
        val conversation: ConversationRepository,
    )

    private data class CatalogAdminRepositories(
        val archive: ArchiveRepository,
        val folder: FolderRepository,
        val group: GroupRepository,
        val identity: IdentityRepository,
        val mcpServer: McpServerRepository,
        val model: ModelRepository,
        val project: ProjectRepository,
    )

    private data class OpsAdminRepositories(
        val passage: PassageRepository,
        val run: RunRepository,
        val job: JobRepository,
        val provider: ProviderRepository,
        val tool: ToolRepository,
    )

    private data class AdminRepositories(
        val archive: ArchiveRepository,
        val folder: FolderRepository,
        val group: GroupRepository,
        val identity: IdentityRepository,
        val mcpServer: McpServerRepository,
        val model: ModelRepository,
        val passage: PassageRepository,
        val project: ProjectRepository,
        val run: RunRepository,
        val job: JobRepository,
        val provider: ProviderRepository,
        val tool: ToolRepository,
    )
}
