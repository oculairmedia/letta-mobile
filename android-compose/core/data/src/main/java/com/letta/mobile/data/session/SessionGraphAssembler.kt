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
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.RuntimeId
import dagger.Lazy
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt-owned assembler that wires session-scoped repositories onto a
 * [SessionGraph]. App consumers receive the same repositories through
 * SessionScoped* Hilt bindings; this class only builds each graph generation.
 *
 * Constructed via [SessionModule] (production) or
 * [createDefaultSessionRepositoryGraphFactory] (tests).
 */
@Singleton
// letta-mobile-g2ff0: agentDao and conversationDao are dagger.Lazy<T> so Room init
// happens lazily on the first dao.get() (inside clearCachesForNewSession /
// repositoryScope.launch), not on the main thread during Hilt graph resolution.
class SessionGraphAssembler constructor(
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

    fun assemble(
        graphId: Long,
        activeConfig: LettaConfig?,
        localRuntimeBackend: LocalLettaBackend?,
        scope: CoroutineScope,
        channelTransport: IChannelTransport,
        settingsRepository: ISettingsRepository?,
    ): SessionGraph {
        val agentRepository = AgentRepository(
            agentApi = agentApi,
            agentDao = agentDao,
            repositoryScope = scope,
            localAgentSource = localAgentSource,
            settingsRepository = settingsRepository,
            transport = channelTransport,
        )
        val useIroh = IrohChannelTransport.shouldUseIroh(activeConfig?.serverUrl)
        val irohConversationListSource = settingsRepository?.let {
            com.letta.mobile.data.repository.IrohAdminRpcConversationListSource(
                channelTransport = channelTransport,
                settingsRepository = it,
            )
        }
        return SessionGraph(
            id = graphId,
            backendDescriptor = localRuntimeBackend?.descriptor ?: remoteLettaDescriptor(activeConfig),
            localRuntimeBackend = localRuntimeBackend,
            scope = scope,
            agentRepository = agentRepository,
            blockRepository = blockRepository,
            allConversationsRepository = AllConversationsRepository(
                conversationApi = conversationApi,
                conversationDao = conversationDao,
                repositoryScope = scope,
                localConversationSource = localConversationSource,
                settingsRepository = settingsRepository,
                irohConversationListSource = irohConversationListSource,
            ),
            channelTransport = channelTransport,
            conversationRepository = ConversationRepository(
                conversationApi = conversationApi,
                agentRepository = agentRepository,
                conversationDao = conversationDao,
                repositoryScope = scope,
                localConversationSource = localConversationSource,
                settingsRepository = settingsRepository,
                irohConversationListSource = irohConversationListSource,
            ),
            cronRepository = CronRepository(
                transport = channelTransport,
                scope = scope,
            ),
            archiveRepository = ArchiveRepository(
                archiveApi = archiveApi,
                irohAdminRpcClient = settingsRepository?.let { settings ->
                    IrohAdminRpcClient(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            folderRepository = FolderRepository(
                folderApi = folderApi,
                irohFolderSource = settingsRepository?.let { settings ->
                    com.letta.mobile.data.repository.IrohAdminRpcFolderSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            groupRepository = GroupRepository(
                groupApi = groupApi,
                irohGroupSource = settingsRepository?.let { settings ->
                    com.letta.mobile.data.repository.IrohAdminRpcGroupSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            identityRepository = IdentityRepository(
                identityApi = identityApi,
                irohIdentitySource = settingsRepository?.let { settings ->
                    IrohAdminRpcIdentitySource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            mcpServerRepository = McpServerRepository(
                mcpServerApi = mcpServerApi,
                irohMcpSource = settingsRepository?.let { settings ->
                    IrohAdminRpcMcpSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            modelRepository = ModelRepository(
                modelApi = modelApi,
                localModelSource = localModelSource,
                settingsRepository = settingsRepository,
                irohModelSource = settingsRepository?.let { settings ->
                    IrohAdminRpcModelSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            passageRepository = PassageRepository(
                passageApi = passageApi,
                irohPassageSource = settingsRepository?.let { settings ->
                    IrohAdminRpcPassageSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            projectRepository = ProjectRepository(
                projectApi = projectApi,
                irohProjectSource = settingsRepository?.let { settings ->
                    IrohAdminRpcProjectSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            projectWorkRepository = ProjectWorkRepository(projectWorkApi),
            runRepository = RunRepository(
                runApi = runApi,
                irohRunSource = settingsRepository?.let { settings ->
                    IrohAdminRpcRunSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            jobRepository = JobRepository(
                jobApi = jobApi,
                irohJobSource = settingsRepository?.let { settings ->
                    IrohAdminRpcJobSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            providerRepository = ProviderRepository(
                providerApi = providerApi,
                irohProviderSource = settingsRepository?.let { settings ->
                    IrohAdminRpcProviderSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            scheduleRepository = if (useIroh) {
                val directory = IrohAdminRpcAgentDirectory(channelTransport)
                IrohScheduleRepository { directory }
            } else {
                ScheduleRepository(scheduleApi)
            },
            selfTodoRepository = SelfTodoRepository(
                transport = channelTransport,
                scope = scope,
            ),
            stepRepository = StepRepository(stepApi),
            subagentRepository = SubagentRepository(
                transport = channelTransport,
                scope = scope,
            ),
            toolRepository = ToolRepository(
                toolApi = toolApi,
                irohToolSource = settingsRepository?.let { settings ->
                    IrohAdminRpcToolSource(
                        channelTransport = channelTransport,
                        settingsRepository = settings,
                    )
                },
            ),
            vibesyncEventStreamRepository = VibesyncEventStreamRepository(
                apiClient = lettaApiClient,
                scope = scope,
            ),
        )
    }

    fun remoteLettaDescriptor(config: LettaConfig?): BackendDescriptor {
        val backendKey = config?.id?.takeIf { it.isNotBlank() } ?: "default"
        val label = config?.serverUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.letta.com"
        return BackendDescriptor(
            backendId = BackendId("remote-letta:$backendKey"),
            runtimeId = RuntimeId("remote-letta:$backendKey"),
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
}
