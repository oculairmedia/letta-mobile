package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.channel.NotificationReplyHandler
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
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal fun runIdsEligibleForCompletionAutoCollapse(messages: List<UiMessage>): Set<String> {
    val newestRunId = messages.asReversed().firstNotNullOfOrNull { message ->
        message.runId?.takeIf { message.role == "assistant" && it.isNotBlank() }
    }
    return newestRunId?.let { setOf(it) }.orEmpty()
}

internal fun collapsedRunIdsAfterRunCompletion(
    messages: List<UiMessage>,
    collapsedRunIds: Set<String>,
    autoCollapseSuppressedRunIds: Set<String>,
): Set<String> {
    val eligibleRunIds = runIdsEligibleForCompletionAutoCollapse(messages)
        .filterNot { it in autoCollapseSuppressedRunIds }
    if (eligibleRunIds.isEmpty()) return collapsedRunIds
    return LinkedHashSet<String>(collapsedRunIds).apply { addAll(eligibleRunIds) }
}

private sealed interface ClientModeBootstrapState {
    data object Idle : ClientModeBootstrapState
    data object NewConversationPending : ClientModeBootstrapState
    data class Ready(val conversationId: String) : ClientModeBootstrapState
}

/**
 * Process-local idempotency for automatic route-provided messages (Android
 * shares, notification/deeplink starters). The per-ViewModel AtomicBoolean is
 * enough for re-resolves in one instance, but Android share delivery can create
 * two equivalent chat route/ViewModel instances before the first navigation is
 * fully settled. In that case both instances carry the same initialMessage and
 * otherwise race into the send path. Keep this narrowly scoped and time-bound
 * so normal manual sends are unaffected.
 */
internal object InitialRouteMessageDeliveryGuard {
    private const val DELIVERY_WINDOW_MS = 30_000L
    private val deliveredAtByKey = linkedMapOf<String, Long>()

    fun key(agentId: String, conversationId: String?, message: String): String = buildString {
        append(agentId)
        append('|')
        append(conversationId.orEmpty())
        append('|')
        append(message)
    }

    fun tryConsume(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(deliveredAtByKey) {
        val cutoff = nowMs - DELIVERY_WINDOW_MS
        val iterator = deliveredAtByKey.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        if (deliveredAtByKey.containsKey(key)) {
            false
        } else {
            deliveredAtByKey[key] = nowMs
            true
        }
    }

    fun resetForTests() = synchronized(deliveredAtByKey) {
        deliveredAtByKey.clear()
    }
}

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
    private val currentConversationTracker: com.letta.mobile.channel.CurrentConversationTracker,
    private val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
    private val notificationReplyHandler: NotificationReplyHandler,
) : ViewModel() {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        private const val CHAT_SEARCH_REMOTE_DEBOUNCE_MS = 180L
        private const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        private const val AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY = "autoCollapseSuppressedRunIds"
        private const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
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
    @Volatile private var activeConversationId: String? =
        requestedConversationArg?.takeIf { it.isNotBlank() }
    private val clientModeEnabled: StateFlow<Boolean> = settingsRepository.observeClientModeEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val initialMessageConsumed = AtomicBoolean(false)
    private var followingDuplicateInitialMessageInFlight = false
    val conversationId: String?
        get() = if (shouldUseClientModeForCurrentRoute) currentClientModeConversationId() else activeConversationId
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

    private val chatSearchController = ChatSearchController(messageRepository)
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

    fun refreshAvailableAgents() {
        viewModelScope.launch {
            runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = 60_000) }
        }
    }

    fun updateChatSearchQuery(query: String) {
        chatSearchJob?.cancel()
        if (query.isBlank()) {
            clearChatSearch()
            return
        }

        val localResults = chatSearchController.localResults(
            query = query,
            state = _uiState.value,
            agentId = agentId,
            conversationId = conversationId,
        )
        _uiState.update {
            it.copy(
                searchQuery = query,
                isSearchActive = true,
                isSearching = true,
                searchResults = localResults,
            )
        }

        chatSearchJob = viewModelScope.launch {
            delay(CHAT_SEARCH_REMOTE_DEBOUNCE_MS)
            try {
                val parsed = chatSearchController.remoteResults(query, agentId)
                _uiState.update { current ->
                    if (current.searchQuery == query) {
                        current.copy(
                            searchResults = chatSearchController.mergeResults(
                                local = chatSearchController.localResults(
                                    query = query,
                                    state = current,
                                    agentId = agentId,
                                    conversationId = conversationId,
                                ),
                                remote = parsed,
                            ),
                            isSearching = false,
                        )
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Chat search failed", e)
                _uiState.update { current ->
                    if (current.searchQuery == query) current.copy(isSearching = false) else current
                }
            }
        }
    }

    fun clearChatSearch() {
        chatSearchJob?.cancel()
        chatSearchJob = null
        _uiState.update {
            it.copy(
                searchQuery = "",
                isSearchActive = false,
                isSearching = false,
                searchResults = persistentListOf(),
            )
        }
    }

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

    private val pendingToolsMap = java.util.concurrent.ConcurrentHashMap<String, PendingToolCall>()
    private var hasSummary = false
    // letta-mobile-flk.6: tracks whether the VM has already resolved its
    // conversation at least once. Used to gate the fresh-route fallback
    // suppression so it only fires on the very first resolveConversationAndLoad
    // call (the actual nav entry). Subsequent re-resolutions — like a
    // mid-session client-mode toggle — are allowed to fall back to the
    // most-recent persisted conversation as before.
    private var hasResolvedConversationOnce: Boolean = false
    // letta-mobile-vynx: a fresh Client Mode route is an explicit
    // "new conversation with no history" bootstrap. Keep that as a real VM
    // state so later Client Mode re-resolves cannot hydrate the agent's most
    // recent cached conversation before the first send. It transitions to
    // Ready only after we have created/persisted the empty Letta conversation.
    private var clientModeBootstrapState: ClientModeBootstrapState =
        if (isFreshRoute) ClientModeBootstrapState.NewConversationPending else ClientModeBootstrapState.Idle

    private fun collapsedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(COLLAPSED_RUN_IDS_KEY)?.toSet().orEmpty()

    private fun autoCollapseSuppressedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY)?.toSet().orEmpty()

    private fun expandedReasoningMessageIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(EXPANDED_REASONING_MESSAGE_IDS_KEY)?.toSet().orEmpty()

    private fun persistCollapsedRunIds(ids: Set<String>) {
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(ids)
        _uiState.value = _uiState.value.copy(collapsedRunIds = ids.toImmutableSet())
    }

    private fun persistExpandedReasoningMessageIds(ids: Set<String>) {
        savedStateHandle[EXPANDED_REASONING_MESSAGE_IDS_KEY] = ArrayList(ids)
        _uiState.value = _uiState.value.copy(expandedReasoningMessageIds = ids.toImmutableSet())
    }

    private fun persistAutoCollapseSuppressedRunIds(ids: Set<String>) {
        savedStateHandle[AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY] = ArrayList(ids)
    }

    fun toggleRunCollapsed(runId: String) {
        val nextCollapsed = collapsedRunIds().toMutableSet()
        val nextSuppressed = autoCollapseSuppressedRunIds().toMutableSet()
        if (nextCollapsed.remove(runId)) {
            // User expanded an auto-collapsed completed run; do not immediately
            // collapse it again on the next timeline emission.
            nextSuppressed.add(runId)
        } else {
            nextCollapsed.add(runId)
            nextSuppressed.remove(runId)
        }
        persistAutoCollapseSuppressedRunIds(nextSuppressed)
        persistCollapsedRunIds(nextCollapsed)
    }

    fun toggleReasoningExpanded(messageId: String) {
        val next = expandedReasoningMessageIds().toMutableSet().apply {
            if (!add(messageId)) remove(messageId)
        }
        persistExpandedReasoningMessageIds(next)
    }

    private fun collapseCompletedRunsByDefault(state: ChatUiState): ChatUiState {
        val nextCollapsed = collapsedRunIdsAfterRunCompletion(
            messages = state.messages,
            collapsedRunIds = state.collapsedRunIds,
            autoCollapseSuppressedRunIds = autoCollapseSuppressedRunIds(),
        )
        if (nextCollapsed == state.collapsedRunIds) return state
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(nextCollapsed)
        return state.copy(collapsedRunIds = nextCollapsed.toImmutableSet())
    }

    private fun collapseCompletedRunsIfStreamingFinished(
        previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState = if (previous.isStreaming && !next.isStreaming) {
        collapseCompletedRunsByDefault(next)
    } else {
        next
    }

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
    private var chatSearchJob: Job? = null
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
        setActiveConversationId = { activeConversationId = it },
        markClientModeBootstrapReady = { clientModeBootstrapState = ClientModeBootstrapState.Ready(it) },
        pendingBootstrapMessages = ::pendingClientModeBootstrapMessages,
        setBootstrapUserMessage = { pendingClientModeBootstrapUserMessage = it },
        clearBootstrapUserMessage = ::clearPendingClientModeBootstrapUserMessage,
        startTimelineObserver = ::startTimelineObserver,
        stopTimelineObserver = ::stopTimelineObserver,
        refreshContextWindow = ::refreshContextWindow,
        collapseCompletedRunsIfStreamingFinished = ::collapseCompletedRunsIfStreamingFinished,
    )
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

    init {
        // letta-mobile-w2hx.6: route arg already pre-populated `activeConversationId`
        // at field init; no shared singleton to seed.
        if (isFreshRoute) {
            setClientModeConversationId(null)
            currentConversationTracker.setCurrent(null)
        }
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
            seedAgentNameFromMemoryCache()
            observeAgentNameCache()
            _uiState.value = _uiState.value.copy(
                collapsedRunIds = collapsedRunIds().toImmutableSet(),
                expandedReasoningMessageIds = expandedReasoningMessageIds().toImmutableSet(),
            )
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
                    sendSteeringMessage(text)
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

    private fun sendSteeringMessage(text: String) {
        val payload = composerController.payloadForSend(text) ?: return
        if (payload.attachments.isNotEmpty()) {
            composerController.setError("Steering updates don't support attachments yet")
            return
        }
        val convId = conversationId ?: activeConversationId ?: currentClientModeConversationId()
        if (convId.isNullOrBlank()) {
            composerController.setError("No active conversation to steer yet")
            return
        }
        val content = payload.text.trim()
        if (content.isBlank()) return
        viewModelScope.launch {
            val localId = runCatching {
                timelineRepository.appendClientModeLocal(
                    conversationId = convId,
                    content = content,
                )
            }.getOrElse { "steer-${java.util.UUID.randomUUID()}" }
            composerController.clearAfterSend()
            runCatching {
                messageRepository.sendSteeringMessage(
                    conversationId = convId,
                    content = content,
                    otid = localId,
                )
            }.onFailure { e ->
                composerController.setError(mapErrorToUserMessage(e.asException(), "Failed to send steering update"))
            }
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
    ) {
        // letta-mobile-flk.6: capture the fresh-route bit at entry. It's
        // only honored on the *first* call (initial nav entry) — see
        // `hasResolvedConversationOnce` and `suppressFreshRouteFallback`
        // below. Subsequent calls (e.g. mid-session client-mode toggle)
        // resolve normally so legacy behavior is preserved.
        val isFirstResolve = !hasResolvedConversationOnce
        hasResolvedConversationOnce = true
        // letta-mobile-flk.6 / w2hx.6: on a genuine fresh-route entry, ensure
        // this VM's local activeConversationId is null so the UI starts truly
        // empty. Pre-w2hx.6 this had to clear an agent-keyed singleton map
        // because that map could be pre-populated by other surfaces (agent
        // list, recents drawer); now activeConversationId lives on this VM
        // alone and only reflects this chat's nav arg, so the clear is just
        // a local null assignment.
        if (isFreshRoute && isFirstResolve && explicitConversationId == null) {
            activeConversationId = null
            android.util.Log.i(
                "AdminChatViewModel",
                "flk6.clearActive agent=$agentId reason=freshRouteInitialResolve",
            )
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.Loading,
                isLoadingMessages = true,
                error = null,
            )

            try {
                if (useClientModeForResolve) {
                    // letta-mobile-c87t (PR 2): when we have a Client Mode
                    // conversationId, route through the timeline observer so
                    // SSE-side persisted messages flow into the UI alongside
                    // the optimistic CLIENT_MODE_HARNESS Locals from
                    // appendClientModeLocal. Without this the chat would only
                    // ever show in-memory bubbles and history wouldn't load.
                    //
                    // Resolution order: explicit nav arg → saved-state-handle
                    // pointer → most-recent server-side conversation for this
                    // agent (Client Mode default-load behavior). Fresh routes
                    // — when the user explicitly opened a new conversation
                    // (freshRouteKey set, or conversationId arg was blank) —
                    // remain in NewConversationPending and must not hydrate a
                    // cached prior conversation on any re-resolve before the
                    // first send creates the empty bootstrap conversation.
                    // See letta-mobile-c87t, flk.6, and vynx.
                    val suppressFreshRouteFallbackClient =
                        clientModeBootstrapState == ClientModeBootstrapState.NewConversationPending ||
                            (isFreshRoute && isFirstResolve)
                    val clientConversationId = explicitConversationId
                        ?: currentClientModeConversationId()?.also {
                            clientModeBootstrapState = ClientModeBootstrapState.Ready(it)
                        }
                        ?: if (!suppressFreshRouteFallbackClient) {
                            runCatching {
                                resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
                            }.getOrNull()?.also { resolved ->
                                setClientModeConversationId(resolved)
                                clientModeBootstrapState = ClientModeBootstrapState.Ready(resolved)
                            }
                        } else {
                            null
                        }
                    currentConversationTracker.setCurrent(clientConversationId)
                    val agent = agentRepository.getCachedAgent(agentId)
                        ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
                    if (clientConversationId != null) {
                        // Make sure the observer is running for this conv;
                        // it'll repopulate _uiState.messages on first emission.
                        startTimelineObserver(clientConversationId)
                        _uiState.value = _uiState.value.copy(
                            agentName = agent?.name ?: _uiState.value.agentName,
                            conversationState = ConversationState.Ready(clientConversationId),
                            // Don't write messages here — the timeline observer
                            // is the source of truth.
                            isLoadingOlderMessages = false,
                            hasMoreOlderMessages = false,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = null,
                        )
                    } else {
                        // Fresh route, no conv yet — keep isolated bootstrap
                        // state and do not observe/hydrate any prior timeline.
                        stopTimelineObserver()
                        _uiState.value = _uiState.value.copy(
                            agentName = agent?.name ?: _uiState.value.agentName,
                            conversationState = ConversationState.NoConversation,
                            messages = pendingClientModeBootstrapMessages(),
                            isLoadingMessages = false,
                            isLoadingOlderMessages = false,
                            hasMoreOlderMessages = false,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = null,
                        )
                    }
                    consumeInitialMessageIfPresent()?.let { message ->
                        sendMessageViaClientMode(message)
                    }
                    return@launch
                }

                // letta-mobile-flk.6: on the *initial* fresh-route entry,
                // suppress the most-recent-conversation fallback so a "New
                // chat" tap doesn't silently hydrate the UI with the
                // agent's previous server-side conversation. Once the VM
                // has resolved at least once (`hasResolvedConversationOnce`),
                // subsequent re-resolutions (e.g. a mid-session client-mode
                // toggle, exercised by the
                // "toggling client mode off restores timeline messages"
                // test) are allowed to fall back to most-recent as before.
                // This mirrors the existing guard in the Client-Mode branch
                // above (line ~629).
                val suppressFreshRouteFallback = isFreshRoute && isFirstResolve
                if (
                    !suppressFreshRouteFallback &&
                    activeConversationId == null &&
                    explicitConversationId == null
                ) {
                    resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
                }

                // letta-mobile-flk.6 / w2hx.6: a fresh-route entry must NOT
                // inherit a prior `activeConversationId` from before this
                // resolve. Pre-w2hx.6 we also had to guard against a
                // process-wide singleton leaking across chats; that's gone.
                // Once `hasResolvedConversationOnce` flips, behave normally.
                val conversationId = if (suppressFreshRouteFallback) {
                    explicitConversationId?.also { activeConversationId = it }
                } else {
                    activeConversationId ?: explicitConversationId?.also { activeConversationId = it }
                }

                if (conversationId == null) {
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.NoConversation,
                        messages = persistentListOf(),
                        isLoadingMessages = false,
                        isLoadingOlderMessages = false,
                        hasMoreOlderMessages = false,
                        error = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.Ready(conversationId),
                        error = null,
                    )
                    loadMessagesInternal()
                }

                consumeInitialMessageIfPresent()?.let { message ->
                    sendMessageViaTimeline(message)
                }
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to resolve conversation", e)
                _uiState.value = _uiState.value.copy(
                    conversationState = ConversationState.Error(
                        message = e.message ?: "Failed to load conversation",
                    ),
                    messages = persistentListOf(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = false,
                    isAgentTyping = false,
                    error = null,
                )
            }
        }
    }

    /**
     * letta-mobile-w2hx.6: resolve the most-recent server-side conversation
     * for this VM's agent and assign it to this VM's [activeConversationId].
     * Replaces ConversationManager.resolveAndSetActiveConversation, which
     * wrote into a process-wide map keyed on agentId — that map could leak
     * one chat's resolved conv into a sibling chat's resolve. The chat row
     * (this VM) now owns its own active id.
     *
     * @return the resolved conversation id, or null if there are none cached
     *   for this agent after the refresh.
     */
    private suspend fun resolveMostRecentConversation(maxAgeMs: Long): String? {
        return chatSessionResolver.resolveMostRecentConversation(agentId, maxAgeMs)
            ?.also { activeConversationId = it }
    }

    private suspend fun loadMessagesInternal() {
        val loadTimer = Telemetry.startTimer("AdminChatVM", "loadMessages")
        val requestedConversationId = activeConversationId ?: explicitConversationId?.also {
            activeConversationId = it
        }
        val currentConversationId = activeConversationId ?: explicitConversationId
        if (requestedConversationId == null) {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    error = null,
                )
            }
            loadTimer.stop("result" to "noConversation")
            return
        }
        val cachedAgent = agentRepository.getCachedAgent(agentId)
        // Timeline is the source of truth — legacy cache is never read here.
        val cachedMessages = emptyList<AppMessage>()
        if (cachedAgent != null || cachedMessages.isNotEmpty()) {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(
                    agentName = cachedAgent?.name ?: _uiState.value.agentName,
                    messages = if (cachedMessages.isNotEmpty()) cachedMessages.toUiMessages().toImmutableList() else _uiState.value.messages,
                    isLoadingMessages = cachedMessages.isEmpty(),
                    error = null,
                )
            }
        } else {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(isLoadingMessages = true)
            }
        }
        try {
            val agent = agentRepository.getAgent(agentId).first()
            if (requestedConversationId != (activeConversationId ?: explicitConversationId)) {
                loadTimer.stop("result" to "staleConversation")
                return
            }
            _uiState.value = _uiState.value.copy(
                agentName = agent.name,
                conversationState = ConversationState.Ready(requestedConversationId),
            )

            // Hand control of _uiState.messages to the timeline observer.
            // It calls TimelineRepository.hydrate() and emits as messages
            // arrive. Keep isLoadingMessages=true until the observer sees
            // the first emission (see startTimelineObserver).
            _uiState.value = _uiState.value.copy(
                isLoadingMessages = true,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
            startTimelineObserver(requestedConversationId)
            loadTimer.stop(
                "conversationId" to requestedConversationId,
                "mode" to "timeline",
            )
        } catch (e: Exception) {
            loadTimer.stopError(e, "conversationId" to requestedConversationId)
            if (requestedConversationId != (activeConversationId ?: explicitConversationId)) {
                return
            }
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.Ready(requestedConversationId),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                error = e.message ?: "Failed to load messages",
            )
        }
    }

    private fun consumeInitialMessageIfPresent(): String? {
        val message = initialMessage?.takeIf { it.isNotBlank() } ?: return null
        if (!initialMessageConsumed.compareAndSet(false, true)) return null

        val deliveryKey = InitialRouteMessageDeliveryGuard.key(
            agentId = agentId,
            conversationId = activeConversationId ?: explicitConversationId ?: currentClientModeConversationId(),
            message = message,
        )
        return if (InitialRouteMessageDeliveryGuard.tryConsume(deliveryKey)) {
            message
        } else {
            android.util.Log.w(
                "AdminChatViewModel",
                "Suppressed duplicate initial route message agent=$agentId " +
                    "conversation=${activeConversationId ?: explicitConversationId ?: currentClientModeConversationId()} " +
                    "messageHash=${message.hashCode()}",
            )
            // A duplicate share route/ViewModel can win the send race before
            // the user-visible route settles. The visible VM still needs to
            // reflect that the shared message is in flight; the timeline
            // observer for the resolved conversation will clear these flags
            // when the assistant response lands.
            followingDuplicateInitialMessageInFlight = true

            // letta-mobile-1yk0: for fresh Client Mode routes, also stage the
            // initial prompt itself. Without this, the visible duplicate route
            // only showed the thinking indicator until the gateway-created
            // conversation migrated into the timeline; then the prompt and
            // response appeared together. That looked like the app ignored the
            // initial message. We do NOT send again here — this is a local
            // mirror of the already in-flight route message.
            if (clientModeEnabled.value && isFreshRoute) {
                val alreadyVisible = pendingClientModeBootstrapUserMessage?.let {
                    it.role == "user" && it.content == message
                } == true || _uiState.value.messages.any {
                    it.role == "user" && it.content == message
                }
                if (!alreadyVisible) {
                    pendingClientModeBootstrapUserMessage = UiMessage(
                        id = "client-user-initial-duplicate-${message.hashCode()}",
                        role = "user",
                        content = message,
                        timestamp = java.time.Instant.now().toString(),
                    )
                }
                _uiState.value = _uiState.value.copy(
                    conversationState = ConversationState.NoConversation,
                    messages = pendingClientModeBootstrapMessages(),
                    isLoadingMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoadingMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                )
            }
            null
        }
    }

    fun loadMessages() {
        if (!shouldUseClientModeForCurrentRoute && activeConversationId == null) {
            resolveConversationAndLoad()
            return
        }
        viewModelScope.launch { loadMessagesInternal() }
    }

    fun retryConversationLoad() {
        resolveConversationAndLoad()
    }

    fun loadOlderMessages() {
        if (clientModeEnabled.value) return
        val conversationId = activeConversationId ?: return
        val currentState = _uiState.value
        if (
            currentState.isLoadingMessages ||
            currentState.isLoadingOlderMessages ||
            !currentState.hasMoreOlderMessages ||
            currentState.isStreaming
        ) {
            return
        }

        val oldestLoadedMessageId = currentState.messages
            .firstOrNull { !it.isPending }
            ?.id
            ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOlderMessages = true)
            try {
                val olderMessages = messageRepository.fetchOlderMessages(
                    agentId = agentId,
                    conversationId = conversationId,
                    beforeMessageId = oldestLoadedMessageId,
                )
                if (conversationId != activeConversationId) {
                    return@launch
                }

                // letta-mobile-23h5 (regression fix 2026-04-19): merge older
                // messages into the per-conversation backfill prefix instead
                // of writing them straight into _uiState.messages — the
                // timeline observer would otherwise overwrite them on its
                // very next emission. Concatenate previously-backfilled
                // pages with this new page so consecutive scroll-ups grow
                // the prefix monotonically.
                val olderUi = olderMessages.toUiMessages()
                val mergedMessages = chatTimelineObserver.mergeOlderPage(
                    conversationId = conversationId,
                    olderMessages = olderUi,
                    existingMessages = _uiState.value.messages,
                )
                _uiState.value = _uiState.value.copy(
                    messages = mergedMessages.toImmutableList(),
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE,
                )
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to load older messages", e)
                if (conversationId == activeConversationId) {
                    _uiState.value = _uiState.value.copy(isLoadingOlderMessages = false)
                }
            }
        }
    }

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
                "active=$activeConversationId",
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
    ) {
        viewModelScope.launch {
            val enqueueTimer = Telemetry.startTimer("AdminChatVM", "send.enqueue")
            composerController.clearAfterSend()
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
            )
            try {
                // letta-mobile-flk.6 / w2hx.6: a fresh-route entry (user
                // tapped "New chat" with no conversationId arg and no in-flight
                // pointer) must NOT inherit a sibling chat's conv id. Pre-w2hx.6
                // we read from a process-wide singleton keyed on agentId; now
                // `activeConversationId` is local to this VM, so a fresh route
                // simply means "no conv yet" and we create a new Letta
                // conversation for this chat.
                var convId: String? = if (isFreshRoute) {
                    explicitConversationId
                } else {
                    explicitConversationId ?: activeConversationId
                }
                if (convId == null) {
                    val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                    convId = conversationRepository.createConversation(agentId, summary).id
                    activeConversationId = convId
                    hasSummary = true
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.Ready(convId),
                    )
                } else if (!hasSummary) {
                    runCatching {
                        val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                        conversationRepository.updateConversation(convId, agentId, summary)
                        hasSummary = true
                    }
                }
                // Ensure observer is attached for this conversation
                startTimelineObserver(convId)
                // Enqueue via the Timeline sync loop — returns immediately after
                // appending the Local event and queuing the HTTP request.
                val otid = if (attachments.isEmpty()) {
                    timelineRepository.sendMessage(convId, text)
                } else {
                    timelineRepository.sendMessage(convId, text, attachments)
                }
                enqueueTimer.stop("otid" to otid, "conversationId" to convId)
                // isStreaming / isAgentTyping will be cleared when we see a Confirmed
                // assistant event land in the timeline (see startTimelineObserver).
            } catch (e: Exception) {
                enqueueTimer.stopError(e)
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
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
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )

            when (val result = chatApprovalCoordinator.submitApproval(
                activeConversationId = activeConversationId,
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
            setClientModeConversationId(null)
            clientModeBootstrapState = if (isFreshRoute) {
                ClientModeBootstrapState.NewConversationPending
            } else {
                ClientModeBootstrapState.Idle
            }
            clearPendingClientModeBootstrapUserMessage()
            currentConversationTracker.setCurrent(null)
            stopTimelineObserver()
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.NoConversation,
                messages = persistentListOf(),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
            return
        }
        viewModelScope.launch {
            try {
                val convId = activeConversationId ?: run {
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
