package com.letta.mobile.feature.chat.screen

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.health.ShimBackendDetector
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.GoalStatus
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.feature.chat.send.ChatSendContext
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.send.LocalRuntimeChatSendStrategy
import com.letta.mobile.feature.chat.send.TimelineChatSendStrategy
import com.letta.mobile.feature.chat.send.WsChatSendStrategy
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.feature.chat.session.ChatSessionInitializer
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.LocalAwareActiveSubagentSource
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsConnectionState
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import com.letta.mobile.feature.chat.coordination.AdminChatA2uiCoordinator
import com.letta.mobile.feature.chat.coordination.AdminChatComposerCoordinator
import com.letta.mobile.data.model.AgentRuntimeBinding
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
import com.letta.mobile.feature.chat.coordination.LocalRuntimeChatSendCoordinator
import com.letta.mobile.feature.chat.coordination.LocalRuntimeRouting
import com.letta.mobile.feature.chat.coordination.ProjectChatCoordinator
import com.letta.mobile.feature.chat.coordination.TimelineSendCoordinator
import com.letta.mobile.feature.chat.coordination.WsChatSendCoordinator
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.ui.chat.render.ChatTransport
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.toConversationState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatSessionReducer



@HiltViewModel
internal class AdminChatViewModel @Inject constructor(
    private val routeArgs: ChatRouteArgs,
    private val messageRepository: MessageRepository,
    private val timelineRepository: com.letta.mobile.data.timeline.TimelineRepository,
    private val agentRepository: IAgentRepository,
    private val blockRepository: IBlockRepository,
    private val bugReportRepository: IBugReportRepository,
    private val folderRepository: IFolderRepository,
    private val conversationRepository: IConversationRepository,
    private val settingsRepository: ISettingsRepository,
    private val sessionManager: SessionManager,
    private val runtimeEventOutbox: RuntimeEventOutbox,
    private val currentConversationTracker: com.letta.mobile.data.channel.CurrentConversationTracker,
    private val notificationDeliveryCoordinator: NotificationDelivery,
    private val shimBackendDetector: ShimBackendDetector,
    private val wsChatBridge: WsChatBridge,
    private val subagentRepository: ISubagentRepository,
    private val slashCommandRepository: ISlashCommandRepository,
    private val clientVersionProvider: ChatClientVersionProvider,
    private val selfTodoRepository: com.letta.mobile.data.repository.api.ISelfTodoRepository,
    private val modelRepository: IModelRepository,
    val attachmentLimits: com.letta.mobile.data.attachment.AttachmentLimits =
        com.letta.mobile.data.attachment.AttachmentLimits.Default,
) : ViewModel() {
    companion object {
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        private const val RESUME_CACHE_MAX_AGE_MS = 60_000L
        private const val TAG = "AdminChatViewModel"
    }

    val agentId: AgentId = AgentId(routeArgs.agentId)
    private var slashCommandsLoadVersion: Long = 0L

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
        val localBound: StateFlow<Boolean> = activeAgent
            .map { AgentRuntimeBinding.isLocalBound(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                AgentRuntimeBinding.isLocalBound(activeAgent.value),
            )
        LocalAwareActiveSubagentSource(
            delegate = WsActiveSubagentSource(subagentRepository, viewModelScope),
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
    val selfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource =
        com.letta.mobile.feature.chat.subagent.WsSelfTodoSource(selfTodoRepository)

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

    private val timelineSendCoordinator: TimelineSendCoordinator by lazy {
        TimelineSendCoordinator(
            scope = viewModelScope,
            agentId = agentId.value,
            isFreshRoute = isFreshRoute,
            explicitConversationId = explicitConversationId,
            conversationRepository = conversationRepository,
            timelineRepository = timelineRepository,
            uiState = _uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
            startTimelineObserver = ::startTimelineObserver,
        )
    }
    private val timelineChatSendStrategy: TimelineChatSendStrategy by lazy {
        TimelineChatSendStrategy(timelineSendCoordinator)
    }
    private val wsChatSendCoordinator: WsChatSendCoordinator by lazy {
        WsChatSendCoordinator(
            scope = viewModelScope,
            agentId = agentId.value,
            activeConfig = { settingsRepository.activeConfig.value },
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = conversationRepository,
            uiState = _uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            isFreshRoute = isFreshRoute,
            setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
            startTimelineObserver = ::startTimelineObserver,
            clientVersionProvider = clientVersionProvider,
            backendDescriptor = { sessionManager.current.backendDescriptor },
            runtimeEventSink = { drafts ->
                drafts.forEach { draft -> runtimeEventOutbox.append(draft) }
            },
        )
    }
    private val wsChatSendStrategy: WsChatSendStrategy by lazy {
        WsChatSendStrategy(wsChatSendCoordinator)
    }
    private val localRuntimeChatSendCoordinator: LocalRuntimeChatSendCoordinator by lazy {
        LocalRuntimeChatSendCoordinator(
            scope = viewModelScope,
            agentId = agentId.value,
            localBackend = { sessionManager.current.localRuntimeBackend },
            timelineRepository = timelineRepository,
            uiState = _uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
            startTimelineObserver = ::startTimelineObserver,
        )
    }
    private val localRuntimeChatSendStrategy: LocalRuntimeChatSendStrategy by lazy {
        LocalRuntimeChatSendStrategy(localRuntimeChatSendCoordinator)
    }
    private val chatSendStrategySelector: ChatSendStrategySelector by lazy {
        ChatSendStrategySelector(
            timelineStrategy = timelineChatSendStrategy,
            wsStrategy = wsChatSendStrategy,
            localStrategy = localRuntimeChatSendStrategy,
        )
    }

    private val composerCoordinator: AdminChatComposerCoordinator by lazy {
        AdminChatComposerCoordinator(
            scope = viewModelScope,
            composerController = composerController,
            chatSendStrategySelector = chatSendStrategySelector,
            chatBannerController = chatBannerController,
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            uiState = _uiState,
            agentId = agentId,
            explicitConversationId = explicitConversationId,
            isShimBackend = { isShimBackend.value },
            sessionManager = sessionManager,
            messageRepository = messageRepository,
            slashCommandRepository = slashCommandRepository,
            timelineChatSendStrategy = timelineChatSendStrategy,
            isStreaming = { _uiState.value.isStreaming },
            projectContextAvailable = projectContext != null,
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

    fun refreshModels() {
        viewModelScope.launch {
            runCatching { modelRepository.refreshLlmModels() }
        }
    }

    fun updateActiveAgentModel(handle: String) {
        viewModelScope.launch {
            try {
                agentRepository.updateAgent(agentId, AgentUpdateParams(model = handle))
                refreshModels()
            } catch (e: Exception) {
                // Surface the failure to the user and re-sync the displayed model state
                val currentModel = activeAgent.value?.model ?: "unknown"
                chatBannerController.showError("Couldn't switch model — still on $currentModel")
                // Re-sync the active agent record so the drawer reflects reality
                runCatching { agentRepository.refreshAgents() }
            }
        }
    }

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
        activeReplyStreams = kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
        uiState = _uiState,
        isClientModeStreamInFlight = { false },
        a2uiThinkingStartMessageCount = { null },
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
            timelineChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = composerCoordinator.chatSendContext(),
            )
        },
        sendMessageViaTimeline = { message ->
            timelineChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = composerCoordinator.chatSendContext(),
            )
        },
        markFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = true },
        localRuntimeRouting = ::localRuntimeRouting,
    )

    private fun localRuntimeRouting(): LocalRuntimeRouting {
        if (sessionManager.current.localRuntimeBackend == null) return LocalRuntimeRouting.Remote
        val conversationId = chatConversationCoordinator.activeConversationId ?: explicitConversationId
        if (conversationId?.startsWith("local-conv-") == true) return LocalRuntimeRouting.LocalBound
        return if (AgentRuntimeBinding.isLocalBound(activeAgent.value)) {
            LocalRuntimeRouting.LocalBound
        } else {
            LocalRuntimeRouting.Blocked()
        }
    }

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
        setComposerError = chatBannerController::showComposerError,
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
            observeLastChatSelection = ::observeLastChatSelection,
            seedAgentNameFromMemoryCache = ::seedAgentNameFromMemoryCache,
            observeAgentNameCache = ::observeAgentNameCache,
            refreshClientModeLocation = { },
            loadProjectAgents = { projectChatCoordinator.loadProjectAgents() },
            loadProjectBrief = { projectChatCoordinator.loadProjectBrief() },
            loadRecentBugReports = { projectChatCoordinator.loadRecentBugReports() },
            resolveConversationAndLoad = ::resolveConversationAndLoad,
        )
    }

    private fun seedAgentNameFromMemoryCache() {
        val cachedName = chatSessionResolver.cachedAgentName(agentId.value) ?: return
        _uiState.update { current ->
            if (current.agentName.isBlank()) current.copy(agentName = cachedName) else current
        }
    }

    private fun observeAgentNameCache() {
        viewModelScope.launch {
            chatSessionResolver.observeCachedAgentName(agentId.value)
                .collect { cachedName ->
                    if (cachedName.isBlank()) return@collect
                    _uiState.update { current ->
                        if (current.agentName.isBlank()) current.copy(agentName = cachedName) else current
                    }
                }
        }
    }

    private fun observeLastChatSelection() {
        settingsRepository.setLastChatSelection(
            agentId = agentId.value,
            agentName = initialAgentName,
            conversationId = conversationId?.value,
        )
        viewModelScope.launch {
            uiState
                .map { state ->
                    val readyConversationId = (state.conversationState as? ConversationState.Ready)?.conversationId
                    state.agentName.takeIf { it.isNotBlank() } to readyConversationId
                }
                .distinctUntilChanged()
                .collect { (resolvedAgentName, resolvedConversationId) ->
                    settingsRepository.setLastChatSelection(
                        agentId = agentId.value,
                        agentName = resolvedAgentName,
                        conversationId = resolvedConversationId,
                    )
                }
        }
    }

    init {
        viewModelScope.launch {
            shimBackendDetector.refreshActive()
            settingsRepository.activeConfigChanges.collect { config ->
                shimBackendDetector.refresh(config)
            }
        }
        observeTransportState()
        observeGoalPushEvents()
        observeGoalStatusRefreshes()
        loadSlashCommands()
        refreshGoalStatus()
        // Force eager initialization of lazy coordinators to start flow subscriptions
        adminChatA2uiCoordinator
        composerCoordinator
        chatSessionInitializer.run()
    }

    private fun loadSlashCommands() {
        viewModelScope.launch {
            val loadVersion = ++slashCommandsLoadVersion
            val builtIns = buildList {
                if (projectContext != null) {
                    add(
                        com.letta.mobile.data.model.SlashCommand(
                            name = "bug",
                            command = "/bug",
                            description = "Open a project bug report",
                            source = "local",
                            installed = true,
                        )
                    )
                }
            }
            // Always expose the FULL global catalog for discovery, marking
            // which are already installed on this agent. Installed ones appear
            // first and styled differently; selecting an uninstalled one
            // installs it to the agent (see selectSlashCommand).
            val agentCommands = slashCommandRepository.listForAgent(agentId.value)
                .getOrElse { e ->
                    Log.d(TAG, "Agent slash commands unavailable: ${e.message}")
                    emptyList()
                }
            val installedNames = agentCommands.map { it.skillName ?: it.name }.toSet()
            val globalCommands = slashCommandRepository.listGlobal()
                .getOrElse { e ->
                    Log.d(TAG, "Global slash commands unavailable: ${e.message}")
                    emptyList()
                }
                .map { it.copy(installed = (it.skillName ?: it.name) in installedNames) }
            // Merge: built-ins + agent-installed + global discovery, deduped by
            // command (installed entries win so the flag stays true).
            val merged = (builtIns + agentCommands + globalCommands)
                .groupBy { it.command }
                .map { (_, dupes) -> dupes.maxByOrNull { it.installed } ?: dupes.first() }
                .sortedWith(compareByDescending<com.letta.mobile.data.model.SlashCommand> { it.installed }
                    .thenBy { it.command })
            Log.d(TAG, "Slash commands loaded: total=${merged.size} installed=${merged.count { it.installed }}")
            if (loadVersion == slashCommandsLoadVersion) {
                composerCoordinator.setSlashCommands(merged)
                if (merged.any { it.source == "builtin_goal" || it.command.startsWith("/goal") }) {
                    refreshGoalStatus()
                }
            }
        }
    }

    private fun observeGoalPushEvents() {
        viewModelScope.launch {
            wsChatBridge.events.collect { event ->
                if (event is WsTimelineEvent.GoalsUpdated) refreshGoalStatus()
            }
        }
    }

    private fun observeGoalStatusRefreshes() {
        viewModelScope.launch {
            isShimBackend
                .filter { it }
                .distinctUntilChanged()
                .collect { refreshGoalStatus() }
        }
    }

    private fun observeTransportState() {
        viewModelScope.launch {
            combine(isShimBackend, wsChatBridge.connection) { isShim, wsState ->
                if (!isShim) return@combine ChatTransport.Rest
                when (wsState) {
                    is WsConnectionState.Idle -> ChatTransport.WsIdle
                    is WsConnectionState.Connecting -> ChatTransport.WsConnecting
                    is WsConnectionState.Connected -> ChatTransport.WsConnected(
                        a2uiEnabled = wsState.a2uiEnabled,
                        catalog = wsState.catalog,
                    )
                    is WsConnectionState.Disconnected -> ChatTransport.WsDisconnected(
                        code = wsState.code,
                        reason = wsState.reason,
                    )
                }
            }.distinctUntilChanged().collect { transport ->
                _uiState.update { it.copy(transport = transport) }
            }
        }
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

    fun loadOlderMessages() = chatHistoryPager.loadOlderMessages(false)

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

    fun onScreenPaused() {
        currentConversationTracker.setCurrent(null)
    }

    private var lastScreenResumedAtMs = Long.MIN_VALUE / 2

    fun onScreenResumed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenResumedAtMs < 200) return
        lastScreenResumedAtMs = now
        val currentId = conversationId?.value
        if (currentId != null) {
            currentConversationTracker.setCurrent(currentId)
            val conn = _sessionState.value.connectionState
            if (conn == ChatConnectionState.Offline || conn == ChatConnectionState.StreamDisconnected) {
                updateSessionState { current ->
                    ChatSessionReducer.retryConnection(
                        current = current,
                        initial = ChatSessionState(),
                    )
                }
                resolveConversationAndLoad()
            }
        }
    }

    override fun onCleared() {
        adminChatA2uiCoordinator.release()
        currentConversationTracker.setCurrent(null)
        super.onCleared()
    }

    // --- Composer coordination delegates ---
    fun handleComposerTextChanged(newText: String): ChatComposerEffect? =
        composerCoordinator.handleComposerTextChanged(newText)

    fun selectSlashCommand(command: com.letta.mobile.data.model.SlashCommand) {
        composerCoordinator.insertSlashCommand(command)
        // Tapping an uninstalled skill command installs it to this agent so
        // the skill becomes available, then refreshes the picker.
        val skillName = command.skillName
        if (skillName != null && !command.installed) {
            viewModelScope.launch {
                slashCommandRepository.installToAgent(agentId.value, skillName)
                    .onSuccess {
                        Log.d(TAG, "Installed skill $skillName to ${agentId.value}")
                        loadSlashCommands()
                    }
                    .onFailure { e -> Log.d(TAG, "Skill install failed for $skillName: ${e.message}") }
            }
        }
    }

    fun uninstallSlashCommand(command: com.letta.mobile.data.model.SlashCommand) {
        val skillName = command.skillName ?: return
        if (!command.installed) return
        viewModelScope.launch {
            slashCommandRepository.uninstallFromAgent(agentId.value, skillName)
                .onSuccess {
                    Log.d(TAG, "Uninstalled skill $skillName from ${agentId.value}")
                    loadSlashCommands()
                }
                .onFailure { e -> Log.d(TAG, "Skill uninstall failed for $skillName: ${e.message}") }
        }
    }

    fun submitComposer(text: String = composerCoordinator.state.value.inputText): ChatComposerEffect? =
        composerCoordinator.submitComposer(text)

    fun sendMessage(text: String) = composerCoordinator.sendMessage(text)

    fun refreshGoalStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGoalStatusLoading = true) }
            slashCommandRepository.getGoalStatus(agentId.value)
                .onSuccess { status -> _uiState.update { it.copy(goalStatus = status.goal?.toUi(), isGoalStatusLoading = false) } }
                .onFailure { _uiState.update { it.copy(isGoalStatusLoading = false) } }
        }
    }

    fun sendGoalCommand(command: String) {
        if (!isShimBackend.value) return
        viewModelScope.launch {
            slashCommandRepository.executeGoalCommand(agentId.value, command)
                .onSuccess { message ->
                    chatBannerController.showComposerError("Goal: $message")
                    refreshGoalStatus()
                }
                .onFailure { error -> chatBannerController.showComposerError(error.message ?: "Goal command failed") }
        }
    }

    fun continueGoal() {
        val objective = _uiState.value.goalStatus?.objective?.takeIf { it.isNotBlank() } ?: return
        composerCoordinator.sendMessage("Continue working on the active goal: $objective. Take the next concrete step, and update or complete the goal when appropriate.")
    }

    fun rerunMessage(message: UiMessage) = composerCoordinator.rerunMessage(message)

    fun interruptRun() = composerCoordinator.interruptRun { adminChatA2uiCoordinator.clearA2uiThinkingOnResponse() }

    private fun GoalStatus.toUi() = GoalStatusUi(
        objective = objective,
        status = status,
        activeTimeSeconds = activeTimeSeconds,
        tokensUsed = tokensUsed,
        tokenBudget = tokenBudget,
    )

    // --- A2UI coordination delegates ---
    fun dismissA2uiSurface(surfaceId: String) = adminChatA2uiCoordinator.dismissA2uiSurface(surfaceId)

    fun submitA2uiAction(action: A2uiAction) = adminChatA2uiCoordinator.submitA2uiAction(action)

    fun markA2uiActionSnackbarShown(id: Long) = adminChatA2uiCoordinator.markA2uiActionSnackbarShown(id)

    fun markA2uiThinkingDelayMessageShown() = adminChatA2uiCoordinator.markA2uiThinkingDelayMessageShown()
}
