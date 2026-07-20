package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.health.ShimBackendDetector
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.feature.chat.session.ChatSessionInitializer
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.LocalAwareActiveSubagentSource
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.runtime.RuntimeEventOutbox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.letta.mobile.feature.chat.coordination.AdminChatAgentSelectionCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatA2uiCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatComposerCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatGoalCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatModelCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatScreenLifecycleCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatSendPipeline
import com.letta.mobile.feature.chat.coordination.AdminChatSlashCommandsCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatTransportCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatTruncatedToolReturnCoordinator
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.subagent.WsSelfTodoSource
import android.os.SystemClock
import com.letta.mobile.feature.chat.coordination.ChatApprovalController
import com.letta.mobile.feature.chat.coordination.ChatApprovalCoordinator
import com.letta.mobile.feature.chat.coordination.ChatClientVersionProvider
import com.letta.mobile.feature.chat.coordination.ChatComposerController
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.ChatConversationCoordinator
import com.letta.mobile.feature.chat.coordination.ChatHistoryPager
import com.letta.mobile.feature.chat.coordination.ChatProjectBindings
import com.letta.mobile.feature.chat.coordination.ChatRunExpansionState
import com.letta.mobile.feature.chat.coordination.ChatSearchCoordinator
import com.letta.mobile.feature.chat.coordination.ChatSessionResolver
import com.letta.mobile.feature.chat.coordination.ChatTimelineObserver
import com.letta.mobile.feature.chat.coordination.LocalRuntimeRouting
import com.letta.mobile.feature.chat.coordination.ProjectChatCoordinator
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.toConversationState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.chat.runtime.ChatSessionReducer



internal fun resolveLocalRuntimeRouting(
    agent: Agent?,
    localRuntimeBackendAvailable: Boolean,
): LocalRuntimeRouting {
    if (agent != null) {
        return if (AgentRuntimeBinding.isLocalBound(agent)) {
            LocalRuntimeRouting.LocalBound
        } else {
            LocalRuntimeRouting.Remote
        }
    }
    return if (localRuntimeBackendAvailable) {
        LocalRuntimeRouting.Blocked()
    } else {
        LocalRuntimeRouting.Remote
    }
}

@HiltViewModel
internal class AdminChatViewModel @Inject constructor(
    private val routeArgs: ChatRouteArgs,
    private val messageRepository: MessageRepository,
    private val timelineRepository: TimelineRepository,
    private val agentRepository: IAgentRepository,
    private val blockRepository: IBlockRepository,
    private val bugReportRepository: IBugReportRepository,
    private val conversationRepository: IConversationRepository,
    private val settingsRepository: ISettingsRepository,
    private val sessionManager: SessionManager,
    private val runtimeEventOutbox: RuntimeEventOutbox,
    private val currentConversationTracker: CurrentConversationTracker,
    private val shimBackendDetector: ShimBackendDetector,
    private val wsChatBridge: WsChatBridge,
    private val subagentRepository: ISubagentRepository,
    private val slashCommandRepository: ISlashCommandRepository,
    private val clientVersionProvider: ChatClientVersionProvider,
    private val selfTodoRepository: ISelfTodoRepository,
    private val modelRepository: IModelRepository,
    val attachmentLimits: AttachmentLimits =
        AttachmentLimits.Default,
) : ViewModel() {
    companion object {
        private const val RESUME_CACHE_MAX_AGE_MS = 60_000L
        private const val TAG = "AdminChatViewModel"
    }

    val agentId: AgentId = AgentId(routeArgs.agentId)
    /**
     * letta-mobile-73o2h.3: WS-backed feed for the active-subagent status
     * bar. Bound here (rather than at the [ChatScreen] call site) so the
     * screen's `activeSubagentSource` parameter stays a single seam that
     * defaults to the fake for previews/tests but gets the real per-socket
     * registry in production. Hot-shared off [viewModelScope].
     */
    // letta-mobile-7vs4s: the bar's WS-backed feed reads the shim's per-socket
    // subagent registry. On the LOCAL embedded runtime there is no shim WS
    // subagent feed, so the WS source can never deliver the terminal
    // transitions its linger accumulator needs — phantom chips would appear on
    // every prompt and never clear. Wrap the WS source so it is GATED to empty
    // while the active agent is local-bound, and passes the real registry
    // through on remote/shim. `by lazy` because the gate reads [activeAgent]
    // (declared below), so construction must happen after field init.
    val activeSubagentSource: ActiveSubagentSource by lazy {
        val parentConversationId = _uiState
            .map { state -> (state.conversationState as? ConversationState.Ready)?.conversationId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, conversationId?.value)
        val localBound: StateFlow<Boolean> = activeAgent
            .map { AgentRuntimeBinding.isLocalBound(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                AgentRuntimeBinding.isLocalBound(activeAgent.value),
            )
        LocalAwareActiveSubagentSource(
            delegate = WsActiveSubagentSource(
                repository = subagentRepository,
                scope = viewModelScope,
                parentAgentId = agentId.value,
                parentConversationId = parentConversationId,
            ),
            isLocalBoundFlow = localBound,
            scope = viewModelScope,
        )
    }

    /**
     * letta-mobile-lgm98: WS-backed feed for the MAIN agent's OWN TodoWrite
     * plan (the "self" chip). Backed by [selfTodoRepository], which the shim
     * feeds via the self-todo broadcast (letta-mobile-jb4gu). Exposed here as
     * a seam so [ChatScreen] can merge the self entry into the active-subagent
     * bar alongside dispatched subagents.
     */
    val selfTodoSource: SelfTodoSource =
        WsSelfTodoSource(selfTodoRepository)

    private val initialAgentName: String? = routeArgs.initialAgentName
    private val initialMessage: String? = routeArgs.initialMessage
    // letta-mobile-aw0dv: the display mode the route asked to open in (e.g.
    // subagent-chip nav requests "interactive"); null = screen default.
    val initialChatMode: String? = routeArgs.initialChatMode
    private val explicitConversationId: String?
        get() = routeArgs.explicitConversationId
    private val isFreshRoute: Boolean
        get() = routeArgs.isFreshRoute
    val scrollToMessageId: String? = routeArgs.scrollToMessageId

    private val explicitNewChat: Boolean
        get() = routeArgs.explicitNewChat
    private val isShimBackend: StateFlow<Boolean> = shimBackendDetector.activeIsShimBackend
        .stateIn(viewModelScope, SharingStarted.Eagerly, shimBackendDetector.cachedActiveIsShimBackend())
    private var followingDuplicateInitialMessageInFlight = false
    val conversationId: ConversationId?
        get() = chatConversationCoordinator.conversationId(false)?.let { ConversationId(it) }
    val projectContext: ProjectChatContext? = routeArgs.projectContext

    private val chatSessionResolver: ChatSessionResolver = ChatSessionResolver(
        agentRepository = agentRepository,
        conversationRepository = conversationRepository,
        backgroundRefreshScope = viewModelScope,
    )
    private val chatApprovalCoordinator: ChatApprovalCoordinator = ChatApprovalCoordinator(messageRepository)
    private val _uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(
        ChatUiState(agentName = initialAgentName.orEmpty())
    )

    private val _sessionState = MutableStateFlow(ChatSessionState())

    private fun updateSessionState(reducerUpdate: (ChatSessionState) -> ChatSessionState) {
        _sessionState.update { current ->
            val next = reducerUpdate(current)
            _uiState.update { ui ->
                ui.copy(
                    // Derive Android ConversationState through the shared ChatScreenStatus
                    // descriptor so both platforms agree on the meaning of each state.
                    conversationState = next.toConversationState(),
                    isLoadingMessages = next.isLoading,
                    error = next.errorMessage,
                )
            }
            next
        }
    }


    val uiState: StateFlow<ChatUiState> by lazy(LazyThreadSafetyMode.NONE) {
        viewModelScope.launchMolecule(mode = Immediate) {
            present()
        }
    }

    @Composable
    private fun present(): ChatUiState {
        val state by _uiState.collectAsState()
        return state
    }

    private val composerController: ChatComposerController = ChatComposerController(limits = attachmentLimits)
    private val chatBannerController: ChatBannerController = ChatBannerController(_uiState, composerController)

    private val sendPipeline: AdminChatSendPipeline by lazy {
        AdminChatSendPipeline(
            scope = viewModelScope,
            agentId = agentId,
            isFreshRoute = isFreshRoute,
            explicitConversationId = explicitConversationId,
            projectContextAvailable = projectContext != null,
            conversationRepository = conversationRepository,
            timelineRepository = timelineRepository,
            settingsRepository = settingsRepository,
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            wsChatBridge = wsChatBridge,
            runtimeEventOutbox = runtimeEventOutbox,
            clientVersionProvider = clientVersionProvider,
            uiState = _uiState,
            composerController = composerController,
            chatBannerController = chatBannerController,
            isShimBackend = {
                isShimBackend.value || settingsRepository.activeConfig.value?.serverUrl
                    ?.trimStart()
                    ?.removePrefix("https://")
                    ?.removePrefix("http://")
                    ?.startsWith("iroh://") == true
            },
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
            startTimelineObserver = ::startTimelineObserver,
        )
    }

    private val composerCoordinator: AdminChatComposerCoordinator
        get() = sendPipeline.composerCoordinator

    private val modelCoordinator: AdminChatModelCoordinator by lazy {
        AdminChatModelCoordinator(
            scope = viewModelScope,
            agentId = agentId,
            agentRepository = agentRepository,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
            activeAgent = activeAgent,
            bannerController = chatBannerController,
        )
    }

    private val goalCoordinator: AdminChatGoalCoordinator by lazy {
        AdminChatGoalCoordinator(
            scope = viewModelScope,
            agentId = agentId,
            slashCommandRepository = slashCommandRepository,
            wsChatBridge = wsChatBridge,
            uiState = _uiState,
            bannerController = chatBannerController,
            isShimBackend = isShimBackend,
            localRuntimeRouting = ::localRuntimeRouting,
            onGoalSlashCommandsDetected = ::refreshGoalStatus,
        )
    }

    private val slashCommandsCoordinator: AdminChatSlashCommandsCoordinator by lazy {
        AdminChatSlashCommandsCoordinator(
            scope = viewModelScope,
            agentId = agentId,
            slashCommandRepository = slashCommandRepository,
            composerCoordinator = composerCoordinator,
            uiState = _uiState,
            projectContext = projectContext,
            localRuntimeRouting = ::localRuntimeRouting,
            goalCoordinator = goalCoordinator,
        )
    }

    private val transportCoordinator: AdminChatTransportCoordinator by lazy {
        AdminChatTransportCoordinator(
            scope = viewModelScope,
            isShimBackend = isShimBackend,
            wsChatBridge = wsChatBridge,
            uiState = _uiState,
        )
    }

    private val agentSelectionCoordinator: AdminChatAgentSelectionCoordinator by lazy {
        AdminChatAgentSelectionCoordinator(
            scope = viewModelScope,
            agentId = agentId,
            initialAgentName = initialAgentName,
            settingsRepository = settingsRepository,
            chatSessionResolver = chatSessionResolver,
            uiState = uiState,
            uiStateMutable = _uiState,
            conversationId = { conversationId?.value },
        )
    }

    private val truncatedToolReturnCoordinator: AdminChatTruncatedToolReturnCoordinator by lazy {
        AdminChatTruncatedToolReturnCoordinator(
            scope = viewModelScope,
            agentId = agentId,
            timelineRepository = timelineRepository,
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            fallbackConversationId = { conversationId?.value },
        )
    }

    private val screenLifecycleCoordinator: AdminChatScreenLifecycleCoordinator by lazy {
        AdminChatScreenLifecycleCoordinator(
            currentConversationTracker = currentConversationTracker,
            conversationId = { conversationId },
            sessionState = _sessionState,
            resolveConversationAndLoad = ::resolveConversationAndLoad,
            updateSessionState = ::updateSessionState,
        )
    }

    private val chatApprovalController: ChatApprovalController = ChatApprovalController(
        scope = viewModelScope,
        coordinator = chatApprovalCoordinator,
        uiState = _uiState,
        bannerController = chatBannerController,
        agentId = agentId.value,
        activeConversationId = { chatConversationCoordinator.activeConversationId },
    )

    private val adminChatA2uiCoordinator: AdminChatA2uiCoordinator by lazy {
        AdminChatA2uiCoordinator(
            scope = viewModelScope,
            wsChatBridge = wsChatBridge,
            uiState = _uiState,
            chatBannerController = chatBannerController,
            activeConversationId = { chatConversationCoordinator.activeConversationId ?: conversationId?.value },
            chatApprovalController = chatApprovalController,
        )
    }

    val composerState: StateFlow<ChatComposerState> by lazy { composerCoordinator.state }
    val inputText: StateFlow<String> by lazy {
        composerState
            .map { it.inputText }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    }

    val chatBackground: StateFlow<ChatBackground> = settingsRepository.getChatBackgroundKey()
        .map { ChatBackground.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackground.Default)

    val chatFontScale: StateFlow<Float> = settingsRepository.getChatFontScale()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    // Master switch for the expressive Jindong activity haptics (streaming +
    // tool-call pattern cues). Default-on; gates the new ChatScreen effects.
    val hapticsEnabled: StateFlow<Boolean> = settingsRepository.getHapticsEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val availableAgents: StateFlow<List<Agent>> = agentRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // getAgent is a one-shot fetch flow (emit + complete), so on its own the
    // drawer's "current model" would never reflect a later updateAgent — the
    // model picker looked like it did nothing (letta-mobile-3icw7). Merge in
    // the repository cache, which updateAgent writes through synchronously.
    val activeAgent: StateFlow<Agent?> = merge(
        agentRepository.getAgent(agentId)
            .map<Agent, Agent?> { it }
            .catch { emit(null) },
        // Reflect cache updates AND removals. Ignore the cache only while it
        // is empty (cold load), so the getAgent fetch above isn't clobbered;
        // once it is populated, a missing agent (delete / backend switch)
        // emits null instead of leaving a stale agent in the drawer.
        agentRepository.agents
            .filter { agents -> agents.isNotEmpty() }
            .map { agents -> agents.find { it.id == agentId } },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val favoriteAgentId: StateFlow<String?> = settingsRepository.favoriteAgentId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.favoriteAgentId.value)

    val activeBackendLabel: StateFlow<String?> = settingsRepository.activeConfig
        .map { it.toBackendLabel() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pinnedAgentIds: StateFlow<Set<String>> = settingsRepository.getPinnedAgentIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val llmModels: StateFlow<List<LlmModel>> = modelRepository.llmModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshModels() = modelCoordinator.refreshModels()

    fun updateActiveAgentModel(handle: String) = modelCoordinator.updateActiveAgentModel(handle)

    fun refreshAvailableAgents() {
        viewModelScope.launch {
            runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = 60_000) }
        }
    }

    fun toggleAgentPinned(agentId: String) {
        viewModelScope.launch {
            settingsRepository.setAgentPinned(agentId, agentId !in pinnedAgentIds.value)
        }
    }

    fun toggleCurrentAgentPinned() = toggleAgentPinned(agentId.value)

    fun updateChatSearchQuery(query: String) = chatSearchCoordinator.updateQuery(query)

    fun clearChatSearch() = chatSearchCoordinator.clear()

    fun setChatBackground(background: ChatBackground) {
        viewModelScope.launch {
            settingsRepository.setChatBackgroundKey(background.key)
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.setChatFontScale(scale)
        }
    }

    private val runExpansionState: ChatRunExpansionState = ChatRunExpansionState(routeArgs.savedStateHandle(), _uiState)

    fun toggleRunCollapsed(runId: String) = runExpansionState.toggleRunCollapsed(runId)

    fun toggleReasoningExpanded(messageId: String) = runExpansionState.toggleReasoningExpanded(messageId)

    fun onTruncatedToolResultExpanded(messageId: String) =
        truncatedToolReturnCoordinator.onTruncatedToolResultExpanded(messageId)

    private fun collapseCompletedRunsIfStreamingFinished(
        previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState = runExpansionState.collapseCompletedRunsIfStreamingFinished(previous, next)

    private val chatSearchCoordinator: ChatSearchCoordinator = ChatSearchCoordinator(
        scope = viewModelScope,
        messageRepository = messageRepository,
        uiState = _uiState,
        agentId = agentId.value,
        conversationId = { conversationId?.value },
    )
    private val chatTimelineObserver: ChatTimelineObserver = ChatTimelineObserver(
        scope = viewModelScope,
        timelineRepository = timelineRepository,
        currentConversationTracker = currentConversationTracker,
        activeReplyStreams = MutableStateFlow(emptySet()),
        uiState = _uiState,
        isClientModeStreamInFlight = { false },
        // letta-mobile-c4igq.7: hold presence across inter-round gaps of a
        // multi-tool turn. wsChatBridge.hasActiveChatTurn is true from turn start
        // until the real terminal (spans all tool rounds), so the thinking/
        // streaming indicator and send button stay steady instead of flickering
        // / looking finished between rounds.
        hasActiveChatTurn = { wsChatBridge.hasActiveChatTurn },
        a2uiThinkingStartMessageCount = { adminChatA2uiCoordinator.getA2uiThinkingStartMessageCount() },
        clearA2uiThinkingOnResponse = { adminChatA2uiCoordinator.clearA2uiThinkingOnResponse() },
        isFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight },
        clearFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = false },
        collapseCompletedRunsIfStreamingFinished = ::collapseCompletedRunsIfStreamingFinished,
        syncA2uiHistorySnapshot = { convId, msgs -> adminChatA2uiCoordinator.syncA2uiHistorySnapshot(convId, msgs) },
    )
    private val chatConversationCoordinator: ChatConversationCoordinator = ChatConversationCoordinator(
        scope = viewModelScope,
        agentId = agentId.value,
        initialMessage = initialMessage,
        explicitConversationId = { explicitConversationId },
        // letta-mobile-9cb37: snapshot of the route's explicit conversation id so
        // an agent switch can't lose it to a restored/stale CONVERSATION_ID_KEY.
        pinnedExplicitConversationId = routeArgs.pinnedExplicitConversationId,
        setRouteConversationId = routeArgs::setRouteConversationId,
        isFreshRoute = isFreshRoute,
        chatSessionResolver = chatSessionResolver,
        agentRepository = agentRepository,
        currentConversationTracker = currentConversationTracker,
        uiState = _uiState,
        updateSessionState = ::updateSessionState,
        pendingClientModeBootstrapMessages = { persistentListOf() },
        setPendingClientModeBootstrapUserMessage = { },
        clearPendingClientModeBootstrapUserMessage = { },
        currentClientModeConversationId = { null },
        setClientModeConversationId = { },
        startTimelineObserver = ::startTimelineObserver,
        stopTimelineObserver = ::stopTimelineObserver,
        reconcileRecentMessages = { convId, reason ->
            timelineRepository.reconcileRecentMessages(agentId.value, convId, reason)
        },
        sendMessageViaClientMode = { message ->
            sendPipeline.timelineChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = composerCoordinator.chatSendContext(),
            )
        },
        sendMessageViaTimeline = { message ->
            sendPipeline.timelineChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = composerCoordinator.chatSendContext(),
            )
        },
        markFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = true },
        localRuntimeRouting = ::localRuntimeRouting,
    )

    private fun localRuntimeRouting(): LocalRuntimeRouting = resolveLocalRuntimeRouting(
        agent = activeAgent.value,
        localRuntimeBackendAvailable = sessionManager.current.localRuntimeBackend != null,
    )

    private val chatHistoryPager: ChatHistoryPager by lazy {
        ChatHistoryPager(
            scope = viewModelScope,
            agentId = agentId.value,
            messageRepository = messageRepository,
            chatTimelineObserver = chatTimelineObserver,
            uiState = _uiState,
            activeConversationId = { chatConversationCoordinator.activeConversationId },
        )
    }
    private val projectChatCoordinator: ProjectChatCoordinator = ProjectChatCoordinator(
        scope = viewModelScope,
        agentId = agentId.value,
        projectContext = projectContext,
        uiState = _uiState,
        agentRepository = agentRepository,
        blockRepository = blockRepository,
        bugReportRepository = bugReportRepository,
        conversationId = { conversationId?.value },
        sendMessage = ::sendMessage,
    )
    val projectBindings: ChatProjectBindings = projectChatCoordinator
    private val chatSessionInitializer: ChatSessionInitializer by lazy {
        ChatSessionInitializer(
            scope = viewModelScope,
            agentId = agentId.value,
            isFreshRoute = isFreshRoute,
            explicitNewChat = explicitNewChat,
            resumeCacheMaxAgeMs = RESUME_CACHE_MAX_AGE_MS,
            projectContext = projectContext,
            settingsRepository = settingsRepository,
            sessionResolver = chatSessionResolver,
            conversationCoordinator = chatConversationCoordinator,
            runExpansionState = runExpansionState,
            currentConversationTracker = currentConversationTracker,
            bannerController = chatBannerController,
            setClientModeConversationId = { },
            refreshAvailableAgents = ::refreshAvailableAgents,
            observeLastChatSelection = agentSelectionCoordinator::observeLastChatSelection,
            seedAgentNameFromMemoryCache = agentSelectionCoordinator::seedAgentNameFromMemoryCache,
            observeAgentNameCache = agentSelectionCoordinator::observeAgentNameCache,
            refreshClientModeLocation = { },
            loadProjectAgents = { projectChatCoordinator.loadProjectAgents() },
            loadProjectBrief = { projectChatCoordinator.loadProjectBrief() },
            loadRecentBugReports = { projectChatCoordinator.loadRecentBugReports() },
            resolveConversationAndLoad = ::resolveConversationAndLoad,
        )
    }

    init {
        viewModelScope.launch {
            shimBackendDetector.refreshActive()
            settingsRepository.activeConfigChanges.collect { config ->
                shimBackendDetector.refresh(config)
            }
        }
        transportCoordinator.startObserving()
        goalCoordinator.startObserving()
        slashCommandsCoordinator.loadSlashCommands()
        refreshGoalStatus()
        adminChatA2uiCoordinator
        sendPipeline.ensureEagerInit()
        chatSessionInitializer.run()
    }

    private fun resolveConversationAndLoad(
        useClientModeForResolve: Boolean = false,
    ) = chatConversationCoordinator.resolveConversationAndLoad(useClientModeForResolve)

    fun loadMessages() = chatConversationCoordinator.loadMessages(false)

    fun retryConversationLoad() {
        updateSessionState { current ->
            ChatSessionReducer.retryConnection(
                current = current,
                initial = ChatSessionState(),
            )
        }
        resolveConversationAndLoad()
    }

    fun loadOlderMessages() {
        if (localRuntimeRouting() == LocalRuntimeRouting.LocalBound) return
        chatHistoryPager.loadOlderMessages(false)
    }

    fun reportComposerError(message: String) = composerCoordinator.reportComposerError(message)

    fun clearComposerError() = composerCoordinator.clearComposerError()

    fun clearError() {
        chatBannerController.clearError()
    }

    fun addAttachment(image: MessageContentPart.Image): Boolean =
        composerCoordinator.addAttachment(image)

    fun removeAttachment(index: Int) = composerCoordinator.removeAttachment(index)

    private fun startTimelineObserver(conversationId: String) {
        adminChatA2uiCoordinator.ensureA2uiConversation(conversationId)
        chatTimelineObserver.start(agentId.value, conversationId)
        // letta-mobile-qfa81 (P4): the iroh active-reconcile poll loop
        // (startIrohRecentReconcileLoop) and its stall-recovery crutch were
        // removed here. P3 (canonical run ids + durable dedupe + parked
        // terminals replayed across redial) guarantees the terminal TurnDone
        // reaches the client, so the client no longer needs to poll
        // reconcileRecentMessages on a timer to un-wedge a dropped terminal.
        // The single post-send reconcile (wired via reconcileRecentMessages in
        // the send coordinator above) still runs.
    }

    private fun stopTimelineObserver() {
        chatTimelineObserver.stop()
    }

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    ) = chatApprovalController.submitApproval(requestId, toolCallIds, approve, reason)

    fun resetMessages() {
        viewModelScope.launch {
            try {
                messageRepository.resetMessages(agentId)
                _uiState.value = _uiState.value.copy(
                    messages = persistentListOf(),
                    messageListChange = ChatMessageListChange.Full,
                )
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to reset messages", e)
            }
        }
    }

    fun updateInputText(text: String) = composerCoordinator.updateInputText(text)

    val canSendMessages: Boolean
        get() = ChatSessionReducer.canSend(_sessionState.value)

    fun onScreenPaused() = screenLifecycleCoordinator.onScreenPaused()

    fun onScreenResumed() = screenLifecycleCoordinator.onScreenResumed()

    override fun onCleared() {
        adminChatA2uiCoordinator.release()
        screenLifecycleCoordinator.onCleared()
        super.onCleared()
    }

    // --- Composer coordination delegates ---
    fun handleComposerTextChanged(newText: String): ChatComposerEffect? =
        composerCoordinator.handleComposerTextChanged(newText)

    fun selectSlashCommand(command: com.letta.mobile.data.model.SlashCommand) =
        slashCommandsCoordinator.selectSlashCommand(command)

    fun uninstallSlashCommand(command: com.letta.mobile.data.model.SlashCommand) =
        slashCommandsCoordinator.uninstallSlashCommand(command)

    fun submitComposer(text: String = composerCoordinator.state.value.inputText): ChatComposerEffect? =
        composerCoordinator.submitComposer(text)

    fun sendMessage(text: String) = composerCoordinator.sendMessage(text)

    fun refreshGoalStatus() = goalCoordinator.refreshGoalStatus()

    fun sendGoalCommand(command: String) = goalCoordinator.sendGoalCommand(command)

    fun continueGoal() = goalCoordinator.continueGoal(::sendMessage)

    fun rerunMessage(message: UiMessage) = composerCoordinator.rerunMessage(message)

    fun interruptRun() = composerCoordinator.interruptRun { adminChatA2uiCoordinator.clearA2uiThinkingOnResponse() }

    // --- A2UI coordination delegates ---
    fun dismissA2uiSurface(surfaceId: String) = adminChatA2uiCoordinator.dismissA2uiSurface(surfaceId)

    fun submitA2uiAction(action: A2uiAction) = adminChatA2uiCoordinator.submitA2uiAction(action)

    fun markA2uiActionSnackbarShown(id: Long) = adminChatA2uiCoordinator.markA2uiActionSnackbarShown(id)

    fun markA2uiThinkingDelayMessageShown() = adminChatA2uiCoordinator.markA2uiThinkingDelayMessageShown()
}
