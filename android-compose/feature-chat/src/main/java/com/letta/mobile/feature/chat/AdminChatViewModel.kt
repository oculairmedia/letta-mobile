package com.letta.mobile.feature.chat

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.bot.repository.ClientModeAgentLocationRepository
import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.health.ShimBackendDetector
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.feature.chat.send.ChatSendContext
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.send.ClientModeChatSendStrategy
import com.letta.mobile.feature.chat.send.TimelineChatSendStrategy
import com.letta.mobile.feature.chat.send.WsChatSendStrategy
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.feature.chat.session.ChatSessionInitializer
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
internal class AdminChatViewModel @Inject constructor(
    private val routeArgs: ChatRouteArgs,
    private val messageRepository: MessageRepository,
    private val timelineRepository: com.letta.mobile.data.timeline.TimelineRepository,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val bugReportRepository: BugReportRepository,
    private val folderRepository: FolderRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val internalBotClient: InternalBotClient,
    private val clientModeChatSender: ClientModeChatSender,
    private val clientModeAgentLocationRepository: ClientModeAgentLocationRepository,
    private val currentConversationTracker: com.letta.mobile.data.channel.CurrentConversationTracker,
    private val notificationDeliveryCoordinator: NotificationDelivery,
    private val notificationReplyHandler: NotificationReplyHandler,
    private val shimBackendDetector: ShimBackendDetector,
    private val wsChatBridge: WsChatBridge,
    private val clientVersionProvider: ChatClientVersionProvider,
    // Exposed (val, not private val) so ChatScreen can hand the same caps to
    // the picker that the composer enforces (lcp-dlj). Default keeps test
    // construction terse — Hilt always injects the bound value at runtime.
    val attachmentLimits: com.letta.mobile.data.attachment.AttachmentLimits =
        com.letta.mobile.data.attachment.AttachmentLimits.Default,
) : ViewModel() {
    companion object {
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        // letta-mobile-h2b8: max age the resume-most-recent flow tolerates
        // before forcing a synchronous refresh of the conversation list.
        // 60s is short enough to feel fresh when the user opens the app
        // after a while, but long enough that warm-launches don't pay the
        // round-trip on every chat open. Matches the cadence
        // ChatSessionResolver uses in its own callers.
        private const val RESUME_CACHE_MAX_AGE_MS = 60_000L
        private const val MAX_A2UI_DEBUG_FRAMES = 12
        private const val A2UI_THINKING_TIMEOUT_MS = 60_000L
        private const val A2UI_THINKING_DELAY_MESSAGE = "Response delayed — check your connection"
        private const val TAG = "AdminChatViewModel"
    }

    val agentId: String = routeArgs.agentId
    private val initialAgentName: String? = routeArgs.initialAgentName
    private val initialMessage: String? = routeArgs.initialMessage
    private val explicitConversationId: String?
        get() = routeArgs.explicitConversationId
    private val isFreshRoute: Boolean
        get() = routeArgs.isFreshRoute
    val scrollToMessageId: String? = routeArgs.scrollToMessageId

    /**
     * letta-mobile-h2b8: distinguishes "user actively chose a new chat"
     * (drawer / conversation-picker "New conversation" path, which always
     * mints a fresh route key) from "screen opened cold without a specific
     * conversation" (startDestination, notification deep-link with no convId,
     * etc.). Only the latter triggers the resume-most-recent flow.
     */
    private val explicitNewChat: Boolean
        get() = routeArgs.explicitNewChat
    // letta-mobile-c87t: previously this predicate was
    //   clientModeEnabled.value && (isFreshRoute || explicitConversationId == null)
    // which collapsed to "client mode only fires for fresh routes" — meaning any
    // existing conversation silently bypassed Client Mode and went direct to Letta.
    // The Client Mode toggle is global per Emmanuel's design; if it's on, ALL
    // conversations should engage with the LettaBot harness. Existing conversations
    // are resumed by passing the Letta conversation ID through to the gateway,
    // which uses Letta Code SDK's resumeSession() to switch into them.
    private val shouldUseClientModeForCurrentRoute: Boolean
        get() = clientModeEnabled.value
    // letta-mobile-w2hx.6: per-VM (per-chat) active conversation id. Replaces
    // the process-wide ConversationManager singleton that was keyed only on
    // agentId — that map could pollute one chat's resolve with another
    // chat's resolved conv (the bug w2hx.6 explicitly calls out).
    //
    // The chat row IS this view-model. Its conversation_id is its own state,
    // never shared across chats, never inherited via agent_id. Initial value
    // comes from the route's explicit nav arg if present; otherwise null
    // until resolveConversationAndLoad assigns one.
    private val clientModeEnabled: StateFlow<Boolean> = settingsRepository.observeClientModeEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val isShimBackend: StateFlow<Boolean> = shimBackendDetector.activeIsShimBackend
        .stateIn(viewModelScope, SharingStarted.Eagerly, shimBackendDetector.cachedActiveIsShimBackend())
    private var followingDuplicateInitialMessageInFlight = false
    val conversationId: String?
        get() = chatConversationCoordinator.conversationId(shouldUseClientModeForCurrentRoute)
    val projectContext: ProjectChatContext? = routeArgs.projectContext

    private val chatSessionResolver = ChatSessionResolver(
        agentRepository = agentRepository,
        conversationRepository = conversationRepository,
        backgroundRefreshScope = viewModelScope,
    )
    private val chatApprovalCoordinator = ChatApprovalCoordinator(messageRepository)
    private val projectAgentActivityLoader = ProjectAgentActivityLoader(
        internalBotClient = internalBotClient,
        agentRepository = agentRepository,
        folderRepository = folderRepository,
    )
    private val _uiState = MutableStateFlow(
        ChatUiState(agentName = initialAgentName.orEmpty())
    )
    private val a2uiSurfaceManager = A2uiSurfaceManager()
    private val pendingA2uiActions = mutableMapOf<String, PendingA2uiAction>()
    private var a2uiThinkingTimeoutJob: Job? = null
    private var a2uiThinkingStartMessageCount: Int? = null
    private var nextA2uiSnackbarId = 0L
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

    private val composerController = ChatComposerController(limits = attachmentLimits)
    private val chatBannerController = ChatBannerController(_uiState, composerController)
    val composerState: StateFlow<ChatComposerState> = composerController.state
    val inputText: StateFlow<String> = composerState
        .map { it.inputText }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val chatBackground: StateFlow<ChatBackground> = settingsRepository.getChatBackgroundKey()
        .map { ChatBackground.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackground.Default)

    val chatFontScale: StateFlow<Float> = settingsRepository.getChatFontScale()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    val availableAgents: StateFlow<List<Agent>> = agentRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteAgentId: StateFlow<String?> = settingsRepository.favoriteAgentId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.favoriteAgentId.value)

    /**
     * letta-mobile-cdlk follow-up: surface the active backend label in the
     * agent drawer so the user can tell at a glance which Letta server this
     * agent is talking to. Mirrors the pill that the home / conversations
     * surfaces show, computed via the same trim rules
     * ([com.letta.mobile.data.model.toBackendLabel]).
     */
    val activeBackendLabel: StateFlow<String?> = settingsRepository.activeConfig
        .map { it.toBackendLabel() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pinnedAgentIds: StateFlow<Set<String>> = settingsRepository.getPinnedAgentIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

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

    fun toggleCurrentAgentPinned() = toggleAgentPinned(agentId)

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

    private val runExpansionState = ChatRunExpansionState(routeArgs.savedStateHandle(), _uiState)

    fun toggleRunCollapsed(runId: String) = runExpansionState.toggleRunCollapsed(runId)

    fun toggleReasoningExpanded(messageId: String) = runExpansionState.toggleReasoningExpanded(messageId)

    private fun collapseCompletedRunsIfStreamingFinished(
        previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState = runExpansionState.collapseCompletedRunsIfStreamingFinished(previous, next)

    /**
     * Fresh-route Client Mode sends do not have a timeline conversation id
     * until the gateway echoes one. Keep exactly one quarantined optimistic
     * USER echo so the composer has immediate feedback. Assistant, reasoning,
     * tool-call, and tool-result chunks are never rendered here; they are
     * buffered separately and replayed into [TimelineRepository] once the
     * gateway returns a real conversation id.
     */
    private var pendingClientModeBootstrapUserMessage: UiMessage? = null

    private fun pendingClientModeBootstrapMessages() =
        listOfNotNull(pendingClientModeBootstrapUserMessage).toImmutableList()

    private fun clearPendingClientModeBootstrapUserMessage() {
        pendingClientModeBootstrapUserMessage = null
    }
    private val chatSearchCoordinator = ChatSearchCoordinator(
        scope = viewModelScope,
        messageRepository = messageRepository,
        uiState = _uiState,
        agentId = agentId,
        conversationId = { conversationId },
    )
    private val chatTimelineObserver = ChatTimelineObserver(
        scope = viewModelScope,
        timelineRepository = timelineRepository,
        currentConversationTracker = currentConversationTracker,
        activeReplyStreams = notificationReplyHandler.activeReplyStreams,
        uiState = _uiState,
        isClientModeStreamInFlight = { clientModeSendCoordinator.isStreamInFlight },
        a2uiThinkingStartMessageCount = { a2uiThinkingStartMessageCount },
        clearA2uiThinkingOnResponse = ::clearA2uiThinkingOnResponse,
        isFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight },
        clearFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = false },
        collapseCompletedRunsIfStreamingFinished = ::collapseCompletedRunsIfStreamingFinished,
    )
    private val chatConversationCoordinator = ChatConversationCoordinator(
        scope = viewModelScope,
        agentId = agentId,
        initialMessage = initialMessage,
        explicitConversationId = explicitConversationId,
        isFreshRoute = isFreshRoute,
        chatSessionResolver = chatSessionResolver,
        agentRepository = agentRepository,
        currentConversationTracker = currentConversationTracker,
        uiState = _uiState,
        pendingClientModeBootstrapMessages = ::pendingClientModeBootstrapMessages,
        setPendingClientModeBootstrapUserMessage = { pendingClientModeBootstrapUserMessage = it },
        clearPendingClientModeBootstrapUserMessage = ::clearPendingClientModeBootstrapUserMessage,
        currentClientModeConversationId = ::currentClientModeConversationId,
        setClientModeConversationId = ::setClientModeConversationId,
        startTimelineObserver = ::startTimelineObserver,
        stopTimelineObserver = ::stopTimelineObserver,
        reconcileRecentMessages = { convId, reason ->
            timelineRepository.reconcileRecentMessages(convId, reason)
        },
        sendMessageViaClientMode = { message ->
            clientModeChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = chatSendContext(isClientModeEnabled = true),
            )
        },
        sendMessageViaTimeline = { message ->
            timelineChatSendStrategy.send(
                text = message,
                attachments = emptyList(),
                context = chatSendContext(isClientModeEnabled = false),
            )
        },
        markFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = true },
    )
    private val chatApprovalController = ChatApprovalController(
        scope = viewModelScope,
        coordinator = chatApprovalCoordinator,
        uiState = _uiState,
        bannerController = chatBannerController,
        activeConversationId = { chatConversationCoordinator.activeConversationId },
    )
    private val clientModeSendCoordinator = ClientModeSendCoordinator(
        scope = viewModelScope,
        agentId = agentId,
        clientModeChatSender = clientModeChatSender,
        timelineRepository = timelineRepository,
        notificationDeliveryCoordinator = notificationDeliveryCoordinator,
        currentConversationTracker = currentConversationTracker,
        uiState = _uiState,
        clearComposerAfterSend = { composerController.clearAfterSend() },
        currentClientModeConversationId = ::currentClientModeConversationId,
        setClientModeConversationId = ::setClientModeConversationId,
        setRouteConversationId = routeArgs::setRouteConversationId,
        setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
        markClientModeBootstrapReady = chatConversationCoordinator::markClientModeBootstrapReady,
        pendingBootstrapMessages = ::pendingClientModeBootstrapMessages,
        setBootstrapUserMessage = { pendingClientModeBootstrapUserMessage = it },
        clearBootstrapUserMessage = ::clearPendingClientModeBootstrapUserMessage,
        showConversationSwap = chatBannerController::showClientModeConversationSwap,
        startTimelineObserver = ::startTimelineObserver,
        stopTimelineObserver = ::stopTimelineObserver,
        refreshContextWindow = { projectChatCoordinator.refreshContextWindow() },
        collapseCompletedRunsIfStreamingFinished = ::collapseCompletedRunsIfStreamingFinished,
    )
    private val timelineSendCoordinator: TimelineSendCoordinator by lazy {
        TimelineSendCoordinator(
            scope = viewModelScope,
            agentId = agentId,
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
            agentId = agentId,
            activeConfig = { settingsRepository.activeConfig.value },
            wsChatBridge = wsChatBridge,
            timelineRepository = timelineRepository,
            conversationRepository = conversationRepository,
            uiState = _uiState,
            clearComposerAfterSend = { composerController.clearAfterSend() },
            activeConversationId = { chatConversationCoordinator.activeConversationId },
            setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
            startTimelineObserver = ::startTimelineObserver,
            clientVersionProvider = clientVersionProvider,
        )
    }
    private val wsChatSendStrategy: WsChatSendStrategy by lazy {
        WsChatSendStrategy(wsChatSendCoordinator)
    }
    private val clientModeChatSendStrategy: ClientModeChatSendStrategy by lazy {
        ClientModeChatSendStrategy(clientModeSendCoordinator)
    }
    private val chatSendStrategySelector: ChatSendStrategySelector by lazy {
        ChatSendStrategySelector(
            timelineStrategy = timelineChatSendStrategy,
            clientModeStrategy = clientModeChatSendStrategy,
            wsStrategy = wsChatSendStrategy,
        )
    }
    private val chatHistoryPager: ChatHistoryPager by lazy {
        ChatHistoryPager(
            scope = viewModelScope,
            agentId = agentId,
            messageRepository = messageRepository,
            chatTimelineObserver = chatTimelineObserver,
            uiState = _uiState,
            activeConversationId = { chatConversationCoordinator.activeConversationId },
        )
    }
    private val projectChatCoordinator = ProjectChatCoordinator(
        scope = viewModelScope,
        agentId = agentId,
        projectContext = projectContext,
        uiState = _uiState,
        clientModeEnabled = clientModeEnabled,
        clientModeAgentLocationRepository = clientModeAgentLocationRepository,
        agentRepository = agentRepository,
        blockRepository = blockRepository,
        bugReportRepository = bugReportRepository,
        projectAgentActivityLoader = projectAgentActivityLoader,
        conversationId = { conversationId },
        setComposerError = chatBannerController::showComposerError,
        sendMessage = ::sendMessage,
    )
    val projectBindings: ChatProjectBindings = projectChatCoordinator
    private val chatSessionInitializer by lazy {
        ChatSessionInitializer(
            scope = viewModelScope,
            agentId = agentId,
            isFreshRoute = isFreshRoute,
            explicitNewChat = explicitNewChat,
            resumeCacheMaxAgeMs = RESUME_CACHE_MAX_AGE_MS,
            projectContext = projectContext,
            settingsRepository = settingsRepository,
            sessionResolver = chatSessionResolver,
            conversationCoordinator = chatConversationCoordinator,
            clientModeCoordinator = clientModeSendCoordinator,
            runExpansionState = runExpansionState,
            currentConversationTracker = currentConversationTracker,
            bannerController = chatBannerController,
            setClientModeConversationId = ::setClientModeConversationId,
            refreshAvailableAgents = ::refreshAvailableAgents,
            observeLastChatSelection = ::observeLastChatSelection,
            seedAgentNameFromMemoryCache = ::seedAgentNameFromMemoryCache,
            observeAgentNameCache = ::observeAgentNameCache,
            refreshClientModeLocation = { projectChatCoordinator.refreshClientModeLocation() },
            loadProjectAgents = { projectChatCoordinator.loadProjectAgents() },
            loadProjectBrief = { projectChatCoordinator.loadProjectBrief() },
            loadRecentBugReports = { projectChatCoordinator.loadRecentBugReports() },
            resolveConversationAndLoad = ::resolveConversationAndLoad,
        )
    }

    private fun seedAgentNameFromMemoryCache() {
        val cachedName = chatSessionResolver.cachedAgentName(agentId) ?: return
        _uiState.update { current ->
            if (current.agentName.isBlank()) current.copy(agentName = cachedName) else current
        }
    }

    private fun observeAgentNameCache() {
        viewModelScope.launch {
            chatSessionResolver.observeCachedAgentName(agentId)
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
            agentId = agentId,
            agentName = initialAgentName,
            conversationId = conversationId,
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
                        agentId = agentId,
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
        observeA2uiEvents()
        observeA2uiActionOutcomes()
        observeTransportState()
        chatSessionInitializer.run()
    }

    private fun observeA2uiEvents() {
        viewModelScope.launch {
            wsChatBridge.a2uiEvents.collect { event ->
                a2uiSurfaceManager.apply(event)
                val frames = event.toDebugFrames()
                _uiState.update { current ->
                    current.copy(
                        a2uiSurfaces = a2uiSurfaceManager.surfaces.value.toPersistentMap(),
                        a2uiDebugFrames = if (frames.isEmpty()) {
                            current.a2uiDebugFrames
                        } else {
                            (current.a2uiDebugFrames + frames)
                                .takeLast(MAX_A2UI_DEBUG_FRAMES)
                                .toImmutableList()
                        },
                        a2uiFrameCount = current.a2uiFrameCount + event.messages.size,
                    )
                }
            }
        }
    }

    /**
     * Derives the [ChatTransport] surfaced in the top-bar chip from the
     * shim-backend flag and the underlying ChannelTransport state.
     *
     * Non-shim backends stay on REST regardless of WS state. Shim
     * backends report whichever phase the WS bridge is in; the
     * Connected variant carries the A2UI negotiation directly off the
     * hello-frame response so the chip can show whether A2UI was
     * accepted (and which catalog) without a second probe.
     */
    private fun observeTransportState() {
        viewModelScope.launch {
            combine(isShimBackend, wsChatBridge.state) { isShim, wsState ->
                if (!isShim) return@combine ChatTransport.Rest
                when (wsState) {
                    is ChannelTransport.State.Idle -> ChatTransport.WsIdle
                    is ChannelTransport.State.Connecting -> ChatTransport.WsConnecting
                    is ChannelTransport.State.Connected -> ChatTransport.WsConnected(
                        a2uiEnabled = wsState.a2uiEnabled,
                        catalog = wsState.a2uiCatalog,
                    )
                    is ChannelTransport.State.Disconnected -> ChatTransport.WsDisconnected(
                        code = wsState.code,
                        reason = wsState.reason,
                    )
                }
            }.distinctUntilChanged().collect { transport ->
                _uiState.update { it.copy(transport = transport) }
            }
        }
    }

    private fun A2uiFrameEvent.toDebugFrames(): List<A2uiDebugFrameUi> = messages.mapIndexed { index, message ->
        A2uiDebugFrameUi(
            id = listOfNotNull(frameId, requestId, message.surfaceId, index.toString()).joinToString(":"),
            transport = transport,
            messageType = message.messageType,
            surfaceId = message.surfaceId.takeIf { it.isNotBlank() },
            conversationId = conversationId,
            requestId = requestId,
        )
    }

    private fun observeA2uiActionOutcomes() {
        viewModelScope.launch {
            wsChatBridge.events.collect { event ->
                val outcome = event as? WsTimelineEvent.UserActionOutcome ?: return@collect
                handleA2uiActionOutcome(outcome)
            }
        }
    }

    private fun handleA2uiActionOutcome(outcome: WsTimelineEvent.UserActionOutcome) {
        val pending = pendingA2uiActions.remove(outcome.frameId)
        if (pending == null) {
            Log.w(TAG, "Dropping stale A2UI action outcome frameId=${outcome.frameId} outcome=${outcome.outcome}")
            return
        }
        _uiState.update { current ->
            val nextCount = (current.a2uiResolvedActionCounters[pending.action.surfaceId] ?: 0) + 1
            current.copy(
                a2uiResolvedActionCounters = current.a2uiResolvedActionCounters
                    .toPersistentMap()
                    .put(pending.action.surfaceId, nextCount),
                a2uiActionSnackbar = outcome.toSnackbar(pending.action),
            )
        }
        if (outcome.expectsFollowUpTurn()) {
            startA2uiThinkingIndicator()
        }
    }

    private fun WsTimelineEvent.UserActionOutcome.expectsFollowUpTurn(): Boolean = outcome.lowercase() in setOf(
        "injected_as_input",
        "matched_approval",
    )

    private fun startA2uiThinkingIndicator() {
        a2uiThinkingTimeoutJob?.cancel()
        a2uiThinkingStartMessageCount = _uiState.value.messages.size
        _uiState.update {
            it.copy(
                isStreaming = true,
                isAgentTyping = true,
                a2uiThinkingDelayMessage = null,
            )
        }
        a2uiThinkingTimeoutJob = viewModelScope.launch {
            delay(A2UI_THINKING_TIMEOUT_MS)
            if (a2uiThinkingStartMessageCount != null) {
                a2uiThinkingStartMessageCount = null
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isAgentTyping = false,
                        a2uiThinkingDelayMessage = A2UI_THINKING_DELAY_MESSAGE,
                    )
                }
            }
        }
    }

    private fun clearA2uiThinkingOnResponse() {
        a2uiThinkingStartMessageCount = null
        a2uiThinkingTimeoutJob?.cancel()
        a2uiThinkingTimeoutJob = null
    }

    fun markA2uiThinkingDelayMessageShown() {
        _uiState.update { it.copy(a2uiThinkingDelayMessage = null) }
    }

    private data class PendingA2uiAction(
        val action: A2uiAction,
        val createdAtMillis: Long = System.currentTimeMillis(),
    )

    private fun WsTimelineEvent.UserActionOutcome.toSnackbar(action: A2uiAction): A2uiActionSnackbarUi {
        val normalized = outcome.lowercase()
        val decision = action.context["decision"]?.jsonPrimitive?.contentOrNull
        val message = when (normalized) {
            "matched_approval" -> when (decision) {
                "deny", "rejected", "timeout" -> "Denied"
                else -> "Approved"
            }
            "injected_as_input" -> "Sent"
            "recorded_only" -> "Saved"
            "rejected" -> reason?.takeIf { it.isNotBlank() }?.let { "Could not send: $it" } ?: "Could not send"
            "error" -> reason?.takeIf { it.isNotBlank() }?.let { "Something went wrong: $it" } ?: "Something went wrong"
            else -> "Action updated"
        }
        val retryable = normalized in setOf("rejected", "error") && idempotent
        return A2uiActionSnackbarUi(
            id = ++nextA2uiSnackbarId,
            message = message,
            actionLabel = if (retryable) "Retry" else null,
            duration = if (retryable) SnackbarDuration.Indefinite else SnackbarDuration.Short,
            retryAction = action.takeIf { retryable },
        )
    }

    fun markA2uiActionSnackbarShown(id: Long) {
        _uiState.update { current ->
            if (current.a2uiActionSnackbar?.id == id) {
                current.copy(a2uiActionSnackbar = null)
            } else {
                current
            }
        }
    }

    fun submitA2uiAction(action: A2uiAction) {
        val result = wsChatBridge.sendA2uiAction(action)
        // letta-mobile-ykkl: log the dispatch outcome so a missing
        // user_action on the wire is diagnosable from adb logcat
        // (which side dropped it: VM, bridge, transport).
        android.util.Log.i(
            "A2UI",
            "submitA2uiAction surfaceId=${action.surfaceId} event=${action.name} result=$result",
        )
        when (result) {
            is A2uiActionDispatchResult.Sent -> {
                pendingA2uiActions[result.frameId] = PendingA2uiAction(action = action)
            }
            is A2uiActionDispatchResult.Queued -> {
                pendingA2uiActions[result.frameId] = PendingA2uiAction(action = action)
                chatBannerController.showComposerError("Action queued until the chat connection returns")
            }
            A2uiActionDispatchResult.Failed -> {
                chatBannerController.showComposerError("Couldn't send action. Check the chat connection and try again.")
            }
        }
    }

    fun tryHandleSlashCommand(text: String): Boolean =
        ChatSlashCommandParser.parse(
            text = text,
            projectContextAvailable = projectContext != null,
        ) != null

    fun handleComposerTextChanged(newText: String): ChatComposerEffect? {
        val composer = composerState.value
        return if (newText.endsWith("\n") && composer.hasSendableContent) {
            submitComposer(composer.inputText)
        } else {
            updateInputText(newText)
            null
        }
    }

    fun submitComposer(text: String = composerState.value.inputText): ChatComposerEffect? {
        return when (ChatSlashCommandParser.parse(text, projectContextAvailable = projectContext != null)) {
            ChatSlashCommand.Bug -> {
                composerController.clearText()
                ChatComposerEffect.OpenBugReport
            }
            null -> {
                if (_uiState.value.isStreaming) {
                    composerController.setError(
                        "Letta does not support free-form steering during an active run yet. Stop the run before sending another message."
                    )
                } else {
                    sendMessage(text)
                }
                null
            }
        }
    }

    fun interruptRun() {
        if (!_uiState.value.isStreaming) return
        clearA2uiThinkingOnResponse()
        val elapsedSinceClientModeStart = android.os.SystemClock.elapsedRealtime() -
            clientModeSendCoordinator.streamStartedAtElapsedMs
        if (clientModeSendCoordinator.isStreamInFlight && elapsedSinceClientModeStart in 0..750) {
            Telemetry.event(
                "AdminChatVM", "clientMode.ignoreImmediateInterrupt",
                "elapsedMs" to elapsedSinceClientModeStart,
            )
            return
        }
        val context = chatSendContext()
        viewModelScope.launch {
            if (context.isShimBackend && !context.isClientModeEnabled) {
                chatBannerController.clearStreamingAfterInterrupt()
                chatSendStrategySelector.cancel(context)
                return@launch
            }
            val runIds = activeRunIds().takeIf { it.isNotEmpty() }
            chatBannerController.clearStreamingAfterInterrupt()
            runCatching {
                messageRepository.cancelMessage(agentId = agentId, runIds = runIds)
            }.onFailure { e ->
                chatBannerController.showMappedError(e.asException(), "Failed to stop run")
            }
            runCatching { internalBotClient.abort() }
            clientModeSendCoordinator.cancelActiveStream("User interrupted active run")
        }
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)

    private fun activeRunIds(): List<String> = _uiState.value.messages
        .asReversed()
        .mapNotNull { it.runId }
        .distinct()
        .take(1)

    private fun resolveConversationAndLoad(
        useClientModeForResolve: Boolean = clientModeEnabled.value,
    ) = chatConversationCoordinator.resolveConversationAndLoad(useClientModeForResolve)

    private suspend fun loadMessagesInternal() = chatConversationCoordinator.loadMessagesInternal()

    fun loadMessages() = chatConversationCoordinator.loadMessages(shouldUseClientModeForCurrentRoute)

    fun retryConversationLoad() {
        resolveConversationAndLoad()
    }

    fun loadOlderMessages() = chatHistoryPager.loadOlderMessages(clientModeEnabled.value)

    fun sendMessage(text: String) {
        when (_uiState.value.conversationState) {
            ConversationState.Loading -> {
                chatBannerController.showConversationStillLoading()
                return
            }
            is ConversationState.Error -> {
                chatBannerController.showRetryConversationLoadBeforeSend()
                return
            }
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> Unit
        }

        val payload = composerController.payloadForSend(text) ?: return
        sendMessagePayload(payload.text, payload.attachments)
    }

    fun rerunMessage(message: UiMessage) {
        val text = message.content.trim()
        if (message.role != "user" || text.isBlank()) return
        sendMessagePayload(text, emptyList())
    }

    private fun sendMessagePayload(
        text: String,
        attachments: List<MessageContentPart.Image>,
    ) {
        val context = chatSendContext()
        chatSendStrategySelector.send(text, attachments, context)
    }

    private fun chatSendContext(
        isClientModeEnabled: Boolean = shouldUseClientModeForCurrentRoute,
    ) = ChatSendContext(
        isClientModeEnabled = isClientModeEnabled,
        explicitConversationId = explicitConversationId,
        isShimBackend = isShimBackend.value,
    )

    private fun currentClientModeConversationId(): String? =
        routeArgs.currentClientModeConversationId()

    private fun setClientModeConversationId(conversationId: String?) {
        routeArgs.setClientModeConversationId(conversationId)
    }

    private fun stopTimelineObserver() {
        chatTimelineObserver.stop()
    }

    fun reportComposerError(message: String) {
        chatBannerController.showComposerError(message)
    }

    fun clearComposerError() {
        chatBannerController.clearComposerError()
    }

    /**
     * Dismiss the conversation-substitution banner emitted when the gateway
     * substituted a fresh conversation for the one we asked it to resume.
     * See `letta-mobile-c87t` and [ClientModeConversationSwap].
     */
    fun dismissClientModeConversationSwap() {
        chatBannerController.dismissClientModeConversationSwap()
    }

    fun addAttachment(image: MessageContentPart.Image): Boolean =
        composerController.addAttachment(image)

    fun removeAttachment(index: Int) {
        composerController.removeAttachment(index)
    }

    /**
     * Subscribe to the [TimelineRepository]'s StateFlow for the given
     * conversation and mirror its events into [_uiState.messages].
     *
     * Idempotent for the SAME conversation — calling repeatedly with the same
     * id while a job is active is a no-op. When the id differs, the previous
     * observer + hydrate-signal job are cancelled and fresh ones are spun up
     * bound to the new conversation.
     *
     * This two-condition guard fixes `letta-mobile-nw2e`: the prior impl only
     * checked `isActive == true` regardless of which conversation the job
     * was bound to, which made switching conversations a silent no-op and
     * left the UI locked onto the first-selected conversation's timeline.
     */
    private fun startTimelineObserver(conversationId: String) {
        chatTimelineObserver.start(conversationId)
    }

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    ) = chatApprovalController.submitApproval(requestId, toolCallIds, approve, reason)

    fun resetMessages() {
        if (shouldUseClientModeForCurrentRoute) {
            chatConversationCoordinator.resetClientModeConversationState()
            return
        }
        viewModelScope.launch {
            try {
                val convId = chatConversationCoordinator.activeConversationId ?: run {
                    chatBannerController.showNoActiveConversationToReset()
                    return@launch
                }
                messageRepository.resetMessages(agentId, convId)
                _uiState.value = _uiState.value.copy(messages = persistentListOf())
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to reset messages", e)
            }
        }
    }

    fun updateInputText(text: String) {
        composerController.updateText(text)
    }

    val canSendMessages: Boolean
        get() = when (_uiState.value.conversationState) {
            ConversationState.Loading -> false
            is ConversationState.Error -> false
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> true
        }

    fun onScreenPaused() {
        currentConversationTracker.setCurrent(null)
    }

    // letta-mobile-ik3u: debounce rapid duplicate onScreenResumed calls
    // (observed 73ms apart) caused by DisposableEffect re-creation when
    // the lifecycleOwner identity changes during composition.
    // Sentinel ensures the very first onScreenResumed is never debounced,
    // including in JVM unit tests where SystemClock.elapsedRealtime() returns 0.
    private var lastScreenResumedAtMs = Long.MIN_VALUE / 2

    fun onScreenResumed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenResumedAtMs < 200) return
        lastScreenResumedAtMs = now
        conversationId?.let { currentConversationTracker.setCurrent(it) }
    }

    override fun onCleared() {
        a2uiThinkingTimeoutJob?.cancel()
        currentConversationTracker.setCurrent(null)
        super.onCleared()
    }

}
