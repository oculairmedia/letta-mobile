package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.bot.repository.ClientModeAgentLocationRepository
import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class AdminChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
    private val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
    private val notificationReplyHandler: NotificationReplyHandler,
) : ViewModel() {
    companion object {
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        private const val CLIENT_MODE_CONVERSATION_ID_KEY = "clientModeConversationId"
    }

    val agentId: String = requireNotNull(savedStateHandle.get<String>("agentId")) {
        "Missing agentId in AdminChatViewModel navigation arguments"
    }
    private val initialAgentName: String? = savedStateHandle.get<String>("agentName")
        ?.takeIf { it.isNotBlank() }
    private val initialMessage: String? = savedStateHandle.get<String>("initialMessage")
    private val requestedConversationArg: String? = savedStateHandle.get<String>("conversationId")
    private val freshRouteKey: Long? = savedStateHandle.get<Long>("freshRouteKey")
    val scrollToMessageId: String? = savedStateHandle.get<String>("scrollToMessageId")
    private val explicitConversationId: String?
        get() = requestedConversationArg?.takeIf { it.isNotBlank() }
    private val isFreshRoute: Boolean
        get() = freshRouteKey != null || requestedConversationArg?.isBlank() == true
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
    private var followingDuplicateInitialMessageInFlight = false
    val conversationId: String?
        get() = chatConversationCoordinator.conversationId(shouldUseClientModeForCurrentRoute)
    val projectContext: ProjectChatContext? = savedStateHandle.get<String>("projectIdentifier")?.let { identifier ->
        val name = savedStateHandle.get<String>("projectName") ?: identifier
        ProjectChatContext(
            identifier = identifier,
            name = name,
            lettaFolderId = savedStateHandle.get<String>("projectLettaFolderId"),
            filesystemPath = savedStateHandle.get<String>("projectFilesystemPath"),
            gitUrl = savedStateHandle.get<String>("projectGitUrl"),
            lastSyncAt = savedStateHandle.get<String>("projectLastSyncAt"),
            activeCodingAgents = savedStateHandle.get<String>("projectActiveCodingAgents"),
        )
    }

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
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val composerController = ChatComposerController()
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

    private val runExpansionState = ChatRunExpansionState(savedStateHandle, _uiState)

    private val pendingToolsMap = java.util.concurrent.ConcurrentHashMap<String, PendingToolCall>()
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
        sendMessageViaClientMode = ::sendMessageViaClientMode,
        sendMessageViaTimeline = { sendMessageViaTimeline(it) },
        markFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = true },
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
        setRouteConversationId = { savedStateHandle["conversationId"] = it },
        setActiveConversationId = chatConversationCoordinator::setActiveConversationId,
        markClientModeBootstrapReady = chatConversationCoordinator::markClientModeBootstrapReady,
        pendingBootstrapMessages = ::pendingClientModeBootstrapMessages,
        setBootstrapUserMessage = { pendingClientModeBootstrapUserMessage = it },
        clearBootstrapUserMessage = ::clearPendingClientModeBootstrapUserMessage,
        startTimelineObserver = ::startTimelineObserver,
        stopTimelineObserver = ::stopTimelineObserver,
        refreshContextWindow = ::refreshContextWindow,
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
        setComposerError = { composerController.setError(it) },
        sendMessage = ::sendMessage,
    )

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
        // letta-mobile-ze5l: when the active backend swaps under us, refresh
        // the agent roster so the drawer / picker reflect the new server's
        // agents. The conversation we're on may not exist on the new server
        // (letta-mobile-iow7 covers the cache-invalidation story); for now
        // we let the timeline observers naturally retry against the new URL.
        viewModelScope.launch {
            settingsRepository.activeConfigChanges.collect {
                refreshAvailableAgents()
            }
        }

        // letta-mobile-w2hx.6: route arg already pre-populated `activeConversationId`
        // at field init; no shared singleton to seed.
        if (isFreshRoute) {
            setClientModeConversationId(null)
            currentConversationTracker.setCurrent(null)
        }
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
            observeLastChatSelection()
            seedAgentNameFromMemoryCache()
            observeAgentNameCache()
            runExpansionState.hydrateUiState()
            viewModelScope.launch {
                settingsRepository.observeClientModeEnabled()
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (!enabled) {
                            clientModeSendCoordinator.cancelActiveStream()
                            _uiState.update {
                                it.copy(
                                    isClientModeEnabled = false,
                                    isStreaming = false,
                                    isAgentTyping = false,
                                    error = null,
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isClientModeEnabled = true,
                                    clientModeLocation = it.clientModeLocation.copy(
                                        defaultPath = it.clientModeLocation.defaultPath
                                            ?: projectContext?.filesystemPath,
                                    ),
                                )
                            }
                            refreshClientModeLocation()
                        }
                        resolveConversationAndLoad(useClientModeForResolve = enabled)
                    }
            }
            if (projectContext != null) {
                loadProjectAgents()
                loadProjectBrief()
                loadRecentBugReports()
            }
        }
    }

    fun refreshClientModeLocation() = projectChatCoordinator.refreshClientModeLocation()

    fun sendClientModeLocationChange(path: String) = projectChatCoordinator.sendClientModeLocationChange(path)

    fun openClientModeLocationPicker() = projectChatCoordinator.openClientModeLocationPicker()

    fun closeClientModeLocationPicker() = projectChatCoordinator.closeClientModeLocationPicker()

    fun browseClientModeLocation(path: String?) = projectChatCoordinator.browseClientModeLocation(path)

    fun selectClientModeLocation(path: String) = projectChatCoordinator.selectClientModeLocation(path)

    fun refreshContextWindow() = projectChatCoordinator.refreshContextWindow()

    fun loadProjectAgents() = projectChatCoordinator.loadProjectAgents()

    fun loadRecentBugReports() = projectChatCoordinator.loadRecentBugReports()

    fun submitStructuredBugReport(draft: ProjectBugReportDraft) = projectChatCoordinator.submitStructuredBugReport(draft)

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
        val elapsedSinceClientModeStart = android.os.SystemClock.elapsedRealtime() -
            clientModeSendCoordinator.streamStartedAtElapsedMs
        if (clientModeSendCoordinator.isStreamInFlight && elapsedSinceClientModeStart in 0..750) {
            Telemetry.event(
                "AdminChatVM", "clientMode.ignoreImmediateInterrupt",
                "elapsedMs" to elapsedSinceClientModeStart,
            )
            return
        }
        viewModelScope.launch {
            val runIds = activeRunIds().takeIf { it.isNotEmpty() }
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    error = null,
                )
            }
            runCatching {
                messageRepository.cancelMessage(agentId = agentId, runIds = runIds)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(error = mapErrorToUserMessage(e.asException(), "Failed to stop run"))
                }
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

    fun loadProjectBrief() = projectChatCoordinator.loadProjectBrief()

    fun saveProjectBriefSection(
        key: ProjectBriefSectionKey,
        content: String,
    ) = projectChatCoordinator.saveProjectBriefSection(key, content)

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
                _uiState.value = _uiState.value.copy(error = "Conversation is still loading")
                return
            }
            is ConversationState.Error -> {
                _uiState.value = _uiState.value.copy(error = "Retry conversation loading before sending a message")
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
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ) {
        val isClientMode = shouldUseClientModeForCurrentRoute
        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to if (isClientMode) "client_mode" else "timeline",
            "length" to text.length,
            "attachments" to attachments.size,
        )
        // letta-mobile-flk.6 debug: log the route + freshness predicates so
        // we can correlate device taps with the lettabot gateway's
        // Auto-resuming vs Forced-new outcome. Remove once verified.
        android.util.Log.i(
            "AdminChatViewModel",
            "flk6.sendMessage agent=$agentId via=${if (isClientMode) "client_mode" else "timeline"} " +
                "isFreshRoute=$isFreshRoute explicitConv=$explicitConversationId " +
                "clientModeConv=${currentClientModeConversationId()} " +
                "active=${chatConversationCoordinator.activeConversationId}",
        )
        if (isClientMode) {
            sendMessageViaClientMode(text, attachments)
            return
        }
        sendMessageViaTimeline(text, attachments)
    }


    private fun sendMessageViaClientMode(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ) {
        clientModeSendCoordinator.send(
            text = text,
            attachments = attachments,
            explicitConversationId = explicitConversationId,
        )
    }

    private fun currentClientModeConversationId(): String? =
        savedStateHandle.get<String>(CLIENT_MODE_CONVERSATION_ID_KEY)?.takeIf { it.isNotBlank() }

    private fun setClientModeConversationId(conversationId: String?) {
        savedStateHandle[CLIENT_MODE_CONVERSATION_ID_KEY] = conversationId?.takeIf { it.isNotBlank() }
    }

    private fun stopTimelineObserver() {
        chatTimelineObserver.stop()
    }

    fun reportComposerError(message: String) {
        composerController.setError(message)
    }

    fun clearComposerError() {
        composerController.clearError()
    }

    /**
     * Dismiss the conversation-substitution banner emitted when the gateway
     * substituted a fresh conversation for the one we asked it to resume.
     * See `letta-mobile-c87t` and [ClientModeConversationSwap].
     */
    fun dismissClientModeConversationSwap() {
        if (_uiState.value.clientModeConversationSwap == null) return
        _uiState.update { it.copy(clientModeConversationSwap = null) }
    }

    fun addAttachment(image: com.letta.mobile.data.model.MessageContentPart.Image): Boolean =
        composerController.addAttachment(image)

    fun removeAttachment(index: Int) {
        composerController.removeAttachment(index)
    }

    /**
     * Timeline-sync send path. Handles optimistic append, streaming, and
     * reconciliation via [timelineRepository]. The timeline observer
     * ([startTimelineObserver]) mirrors state changes into [_uiState.messages]
     * automatically. Returns immediately after enqueueing the send — all
     * visible state transitions flow through the single sync loop.
     */
    private fun sendMessageViaTimeline(
        text: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
    ) = timelineSendCoordinator.send(text, attachments)

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
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )

            when (val result = chatApprovalCoordinator.submitApproval(
                activeConversationId = chatConversationCoordinator.activeConversationId,
                requestId = requestId,
                toolCallIds = toolCallIds,
                approve = approve,
                reason = reason,
            )) {
                ChatApprovalResult.Submitted -> {
                    // Don't reload - approval response will come via streaming
                    _uiState.value = _uiState.value.copy(
                        isStreaming = false,
                        isAgentTyping = false,
                        activeApprovalRequestId = null,
                    )
                }
                ChatApprovalResult.MissingActiveConversation -> {
                    _uiState.value = _uiState.value.copy(
                        error = "No active conversation available for approval",
                        isStreaming = false,
                        isAgentTyping = false,
                        activeApprovalRequestId = null,
                    )
                }
                is ChatApprovalResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isStreaming = false,
                        isAgentTyping = false,
                        activeApprovalRequestId = null,
                    )
                }
            }
        }
    }

    fun resetMessages() {
        if (shouldUseClientModeForCurrentRoute) {
            chatConversationCoordinator.resetClientModeConversationState()
            return
        }
        viewModelScope.launch {
            try {
                val convId = chatConversationCoordinator.activeConversationId ?: run {
                    _uiState.value = _uiState.value.copy(error = "No active conversation to reset")
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
        android.util.Log.w("AdminChatVM-LIFECYCLE", "onScreenPaused clearing tracker prev=${currentConversationTracker.current}")
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
        android.util.Log.w("AdminChatVM-LIFECYCLE", "onScreenResumed restoring tracker to convId=$conversationId")
        conversationId?.let { currentConversationTracker.setCurrent(it) }
    }

    override fun onCleared() {
        currentConversationTracker.setCurrent(null)
        super.onCleared()
    }

}
