package com.letta.mobile.data.session

import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.context.contextTokenReadingsOf
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.IrohAdminRpcMcpSource
import com.letta.mobile.data.repository.IrohAdminRpcModelSource
import com.letta.mobile.data.repository.IrohAdminRpcPassageSource
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.IrohAdminRpcProviderSource
import com.letta.mobile.data.repository.IrohAdminRpcRunSource
import com.letta.mobile.data.repository.IrohAdminRpcToolSource
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
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

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
    private val agentDao: Lazy<AgentDao>,
    private val conversationDao: Lazy<ConversationDao>,
    /** letta-mobile-ckmub.4: the backend seam; production binds [SessionRemoteSources.None]. */
    private val remoteSources: SessionRemoteSources = SessionRemoteSources.None,
    private val blockRepository: BlockRepository? = null,
    private val localConversationSource: LocalRuntimeConversationSource? = null,
    private val localAgentSource: LocalRuntimeAgentSource? = null,
    private val localModelSource: LocalRuntimeModelSource? = null,
    /** letta-mobile-wdm6i: where the context chip's last readings survive a restart. */
    private val settingsStore: SecureSettingsStore? = null,
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
            capturedConfig = request.activeConfig,
            backendDescriptor = request.backendDescriptor,
            conversationCursorStore = request.capturedCursorStore,
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
            mcpServerRepository = adminRepositories.mcpServer,
            modelRepository = adminRepositories.model,
            passageRepository = adminRepositories.passage,
            projectRepository = adminRepositories.project,
            projectWorkRepository = ProjectWorkRepository(remoteSources.projectWork),
            runRepository = adminRepositories.run,
            providerRepository = adminRepositories.provider,
            scheduleRepository = createScheduleRepository(useIroh, request.channelTransport),
            selfTodoRepository = SelfTodoRepository(
                transport = request.channelTransport,
                scope = request.scope,
            ),
            stepRepository = StepRepository(remoteSources.steps),
            subagentRepository = SubagentRepository(
                transport = request.channelTransport,
                scope = request.scope,
            ),
            toolRepository = adminRepositories.tool,
            vibesyncEventStreamRepository = VibesyncEventStreamRepository(request.scope),
            contextTokenReadings = contextTokenReadingsOf(
                transport = request.channelTransport,
                scope = request.scope,
                snapshots = settingsStore?.let(::ContextReadingSnapshots),
            ),
        )
    }

    private fun createAgentRepository(request: SessionGraphAssembleRequest): AgentRepository =
        AgentRepository(
            remote = remoteSources.agents,
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
        val irohConversationListSource = request.irohSource(::IrohAdminRpcConversationListSource)
        return ConversationRepos(
            allConversations = AllConversationsRepository(
                remote = remoteSources.conversations,
                conversationDao = conversationDao,
                repositoryScope = request.scope,
                localConversationSource = localConversationSource,
                settingsRepository = request.settingsRepository,
                irohConversationListSource = irohConversationListSource,
                // letta-mobile-lks7m: conversations started on another device arrive as pushes.
                transport = request.channelTransport,
            ),
            conversation = ConversationRepository(
                remote = remoteSources.conversations,
                agentRepository = agentRepository,
                conversationDao = conversationDao,
                repositoryScope = request.scope,
                localConversationSource = localConversationSource,
                settingsRepository = request.settingsRepository,
                irohConversationListSource = irohConversationListSource,
                // letta-mobile-xj85j: the in-chat picker follows other devices' changes too.
                transport = request.channelTransport,
            ),
        )
    }

    private fun createAdminRepositories(request: SessionGraphAssembleRequest): AdminRepositories =
        AdminRepositories(
            mcpServer = McpServerRepository(remoteSources.mcpServers, request.irohSource(::IrohAdminRpcMcpSource)),
            model = ModelRepository(
                remote = remoteSources.models,
                localModelSource = localModelSource,
                settingsRepository = request.settingsRepository,
                irohModelSource = request.irohSource(::IrohAdminRpcModelSource),
            ),
            passage = PassageRepository(remoteSources.passages, request.irohSource(::IrohAdminRpcPassageSource)),
            project = ProjectRepository(remoteSources.projects, request.irohSource(::IrohAdminRpcProjectSource)),
            run = RunRepository(remoteSources.runs, request.irohSource(::IrohAdminRpcRunSource)),
            provider = ProviderRepository(remoteSources.providers, request.irohSource(::IrohAdminRpcProviderSource)),
            tool = ToolRepository(remoteSources.tools, request.irohSource(::IrohAdminRpcToolSource)),
        )

    /** An Iroh admin_rpc source over this graph's transport, when the graph has settings to scope it. */
    private fun <T> SessionGraphAssembleRequest.irohSource(source: (IChannelTransport, ISettingsRepository) -> T): T? =
        settingsRepository?.let { source(channelTransport, it) }

    private fun createScheduleRepository(
        useIroh: Boolean,
        channelTransport: IChannelTransport,
    ) = if (useIroh) {
        val directory = IrohAdminRpcAgentDirectory(channelTransport)
        IrohScheduleRepository { directory }
    } else {
        ScheduleRepository(remoteSources.schedules)
    }

    fun remoteLettaDescriptor(config: LettaConfig?): BackendDescriptor =
        remoteLettaBackendDescriptor(config, ANDROID_REMOTE_LETTA_ID_PREFIX)

    private data class ConversationRepos(
        val allConversations: AllConversationsRepository,
        val conversation: ConversationRepository,
    )

    private data class AdminRepositories(
        val mcpServer: McpServerRepository,
        val model: ModelRepository,
        val passage: PassageRepository,
        val project: ProjectRepository,
        val run: RunRepository,
        val provider: ProviderRepository,
        val tool: ToolRepository,
    )
}
