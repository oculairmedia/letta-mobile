package com.letta.mobile.data.session

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.session.graph.AdminRemoteRoutes
import com.letta.mobile.data.session.graph.HostSessionRepositories
import com.letta.mobile.data.session.graph.SessionGraphIdentity
import com.letta.mobile.data.session.graph.SharedSessionGraph
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.graph.adminRepositories
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.ProjectWorkRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.repository.IrohAdminRpcConversationListSource
import com.letta.mobile.data.repository.routedBackendKind
import com.letta.mobile.data.transport.api.IChannelTransport
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
        // letta-mobile-by4vm.24: the transport-bound repositories every host builds alike.
        val transportBound = SharedSessionGraph.transportBound(
            transport = request.channelTransport,
            scope = request.scope,
            snapshots = settingsStore?.let(::ContextReadingSnapshots),
        )
        val useIroh = request.activeConfig.routedBackendKind() == BackendKind.IROH
        val repositories = SharedSessionRepositoryGraph(
            identity = SessionGraphIdentity(
                id = request.graphId,
                backendDescriptor = request.backendDescriptor,
                localRuntimeBackend = request.localRuntimeBackend,
                channelTransport = request.channelTransport,
                capabilities = sessionCapabilities(request),
            ),
            host = HostSessionRepositories(
                agentRepository = agentRepository,
                blockRepository = adminRepositories.blockRepository,
                conversationRepository = conversationRepos.conversation,
                mcpServerRepository = adminRepositories.mcpServerRepository,
                modelRepository = adminRepositories.modelRepository,
                passageRepository = adminRepositories.passageRepository,
                projectRepository = adminRepositories.projectRepository,
                projectWorkRepository = ProjectWorkRepository(remoteSources.projectWork),
                runRepository = adminRepositories.runRepository,
                providerRepository = adminRepositories.providerRepository,
                scheduleRepository = createScheduleRepository(useIroh, request.channelTransport),
                stepRepository = StepRepository(remoteSources.steps.orNull()),
                toolRepository = adminRepositories.toolRepository,
                vibesyncEventStreamRepository = VibesyncEventStreamRepository(request.scope),
            ),
            transportBound = transportBound,
        )
        return SessionGraph(
            repositories = repositories,
            localRuntimeBackend = request.localRuntimeBackend,
            scope = request.scope,
            allConversationsRepository = conversationRepos.allConversations,
            conversationCursorStore = request.capturedCursorStore,
            capturedConfig = request.activeConfig,
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

    // letta-mobile-by4vm.87/.103: the admin repositories (tools and memory blocks included) desktop
    // builds too, over the same builder.
    private fun createAdminRepositories(request: SessionGraphAssembleRequest) =
        SharedSessionGraph.adminRepositories(
            transport = request.channelTransport,
            settings = request.settingsRepository,
            remotes = AdminRemoteRoutes(
                mcpServers = remoteSources.mcpServers,
                models = remoteSources.models,
                passages = remoteSources.passages,
                projects = remoteSources.projects,
                providers = remoteSources.providers,
                runs = remoteSources.runs,
                tools = remoteSources.tools,
            ),
            localModelSource = localModelSource,
        )

    /**
     * letta-mobile-by4vm.13: what this graph serves — the Android row of the
     * [com.letta.mobile.data.backend.SessionRouteMatrix] for the config's backend kind, plus any route
     * a bound remote source adds. The Iroh host's advertised features are read live.
     */
    private fun sessionCapabilities(request: SessionGraphAssembleRequest): SessionCapabilities =
        SessionCapabilities.forSession(
            platform = SessionPlatform.ANDROID,
            kind = request.activeConfig?.routedBackendKind(),
            hostCapabilities = { request.channelTransport.hostCapabilities },
            extraBound = remoteSources.boundRoutes(),
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
}
