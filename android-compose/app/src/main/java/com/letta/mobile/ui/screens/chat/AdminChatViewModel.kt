package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.channel.NotificationCandidatePhase
import com.letta.mobile.channel.NotificationCandidateSource
import com.letta.mobile.channel.NotificationDeliveryCandidate
import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.channel.NotificationReplyHandler
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
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
import kotlinx.collections.immutable.toImmutableMap
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
        private const val MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS = 128
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

    private var clientModeStreamJob: Job? = null

    /**
     * letta-mobile-5s1n (regression fix): explicit "Client Mode stream in
     * flight" flag, set BEFORE any timeline mutations and cleared after the
     * stream coroutine's `finally`. Used by the timeline observer to know
     * whether to preserve `isStreaming` / `isAgentTyping` across emissions.
     *
     * We can't rely on `clientModeStreamJob?.isActive`: with
     * `UnconfinedTestDispatcher` (and equivalently fast main-thread
     * dispatch in production), the launched coroutine begins running
     * synchronously inside `viewModelScope.launch { ... }` and triggers
     * observer emissions BEFORE the `clientModeStreamJob` assignment
     * completes — leaving the field at its old (often null) value when
     * the observer reads it.
     */
    @Volatile private var clientModeStreamInFlight: Boolean = false
    @Volatile private var clientModeStreamStartedAtElapsedMs: Long = 0L
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
    private var pendingClientModeStreamSessionId: String? = null
    private val pendingClientModeStreamChunks = ArrayDeque<BotStreamChunk>()
    private var chatSearchJob: Job? = null
    private val chatTimelineObserver = ChatTimelineObserver(
        scope = viewModelScope,
        timelineRepository = timelineRepository,
        currentConversationTracker = currentConversationTracker,
        activeReplyStreams = notificationReplyHandler.activeReplyStreams,
        uiState = _uiState,
        isClientModeStreamInFlight = { clientModeStreamInFlight },
        isFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight },
        clearFollowingDuplicateInitialMessageInFlight = { followingDuplicateInitialMessageInFlight = false },
        collapseCompletedRunsIfStreamingFinished = ::collapseCompletedRunsIfStreamingFinished,
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
                            clientModeStreamJob?.cancel()
                            resetPreConversationClientModeBuffer()
                            clientModeStreamInFlight = false
                            clientModeStreamStartedAtElapsedMs = 0L
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

    fun refreshClientModeLocation() {
        if (agentId.isBlank() || !clientModeEnabled.value) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    clientModeLocation = it.clientModeLocation.copy(
                        isLoading = true,
                        error = null,
                        defaultPath = it.clientModeLocation.defaultPath ?: projectContext?.filesystemPath,
                    )
                )
            }
            runCatching { clientModeAgentLocationRepository.getLocation(agentId) }
                .onSuccess { location ->
                    _uiState.update {
                        val previous = it.clientModeLocation
                        it.copy(
                            clientModeLocation = previous.copy(
                                isLoading = false,
                                currentPath = location?.currentPath ?: previous.currentPath,
                                defaultPath = location?.defaultPath ?: previous.defaultPath ?: projectContext?.filesystemPath,
                                error = null,
                            )
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            clientModeLocation = it.clientModeLocation.copy(
                                isLoading = false,
                                error = mapErrorToUserMessage(e.asException(), "Failed to refresh agent location"),
                            )
                        )
                    }
                }
        }
    }

    fun sendClientModeLocationChange(path: String) {
        val normalized = path.trim()
        if (normalized.isBlank()) return
        if (_uiState.value.isStreaming) {
            composerController.setError("Wait for the current response before changing location")
            return
        }
        _uiState.update {
            it.copy(
                clientModeLocation = it.clientModeLocation.copy(
                    lastRequestedPath = normalized,
                    error = null,
                )
            )
        }
        sendMessage(buildClientModeLocationPrompt(normalized))
    }

    fun openClientModeLocationPicker() {
        if (!clientModeEnabled.value) return
        val initialPath = _uiState.value.clientModeLocation.currentPath
            ?: _uiState.value.clientModeLocation.lastRequestedPath
            ?: _uiState.value.clientModeLocation.defaultPath
        _uiState.update {
            it.copy(
                clientModeFilesystemPicker = it.clientModeFilesystemPicker.copy(
                    isVisible = true,
                    error = null,
                )
            )
        }
        browseClientModeLocation(initialPath)
    }

    fun closeClientModeLocationPicker() {
        _uiState.update {
            it.copy(clientModeFilesystemPicker = it.clientModeFilesystemPicker.copy(isVisible = false))
        }
    }

    fun browseClientModeLocation(path: String?) {
        if (!clientModeEnabled.value) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    clientModeFilesystemPicker = it.clientModeFilesystemPicker.copy(
                        isLoading = true,
                        error = null,
                    )
                )
            }
            runCatching { clientModeAgentLocationRepository.browseDirectories(path) }
                .onSuccess { listing ->
                    _uiState.update {
                        it.copy(
                            clientModeFilesystemPicker = it.clientModeFilesystemPicker.copy(
                                isVisible = true,
                                isLoading = false,
                                path = listing?.path ?: path,
                                parent = listing?.parent,
                                entries = listing?.entries ?: persistentListOf(),
                                truncated = listing?.truncated ?: false,
                                error = if (listing == null) "Client-mode server is not configured" else null,
                            )
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            clientModeFilesystemPicker = it.clientModeFilesystemPicker.copy(
                                isLoading = false,
                                error = mapErrorToUserMessage(e.asException(), "Failed to browse server filesystem"),
                            )
                        )
                    }
                }
        }
    }

    fun selectClientModeLocation(path: String) {
        closeClientModeLocationPicker()
        sendClientModeLocationChange(path)
    }

    fun refreshContextWindow() {
        if (agentId.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(contextWindow = it.contextWindow.copy(isLoading = true, error = null))
            }
            try {
                val overview = agentRepository.getContextWindow(agentId, conversationId)
                _uiState.update {
                    it.copy(
                        contextWindow = ContextWindowUiState(
                            isLoading = false,
                            maxTokens = overview.contextWindowSizeMax,
                            currentTokens = overview.contextWindowSizeCurrent,
                            messageCount = overview.numMessages,
                            systemTokens = overview.numTokensSystem,
                            coreMemoryTokens = overview.numTokensCoreMemory,
                            externalMemoryTokens = overview.numTokensExternalMemorySummary,
                            summaryMemoryTokens = overview.numTokensSummaryMemory,
                            toolTokens = overview.numTokensFunctionsDefinitions +
                                overview.numTokensToolUsageRules +
                                overview.numTokensDirectories +
                                overview.numTokensMemoryFilesystem,
                            messageTokens = overview.numTokensMessages,
                            archivalMemoryCount = overview.numArchivalMemory,
                            recallMemoryCount = overview.numRecallMemory,
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        contextWindow = it.contextWindow.copy(
                            isLoading = false,
                            error = mapErrorToUserMessage(e, "Failed to load context window"),
                        )
                    )
                }
            }
        }
    }

    fun loadProjectAgents() {
        val project = projectContext ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectAgents = _uiState.value.projectAgents.copy(isLoading = true, error = null)
            )
            try {
                val activities = projectAgentActivityLoader.load(project, agentId)
                _uiState.value = _uiState.value.copy(
                    projectAgents = ProjectAgentsUiState(
                        isLoading = false,
                        agents = activities.toImmutableList(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectAgents = _uiState.value.projectAgents.copy(
                        isLoading = false,
                        error = mapErrorToUserMessage(e, "Failed to load active agents"),
                    )
                )
            }
        }
    }

    fun loadRecentBugReports() {
        val projectIdentifier = projectContext?.identifier ?: return
        viewModelScope.launch {
            try {
                val recent = bugReportRepository.getRecentBugReports(projectIdentifier)
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        recentReports = recent.toImmutableList(),
                        error = null,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        error = mapErrorToUserMessage(e, "Failed to load recent bug reports"),
                    )
                )
            }
        }
    }

    fun submitStructuredBugReport(draft: ProjectBugReportDraft) {
        val project = projectContext ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                bugReports = _uiState.value.bugReports.copy(isSubmitting = true, error = null)
            )
            try {
                val prompt = buildBugReportPrompt(draft)
                val logged = bugReportRepository.logBugReport(
                    ProjectBugReport(
                        projectIdentifier = project.identifier,
                        title = draft.title.trim(),
                        description = draft.description.trim(),
                        severity = draft.severity.wireValue,
                        tags = draft.tags,
                        attachmentReferences = draft.attachmentReferences,
                        structuredPrompt = prompt,
                        createdAt = java.time.Instant.now().toString(),
                    )
                )
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        isSubmitting = false,
                        lastSubmittedPrompt = prompt,
                        recentReports = (listOf(logged) + _uiState.value.bugReports.recentReports
                            .filterNot { it.id == logged.id }
                            .take(4)).toImmutableList(),
                    )
                )
                sendMessage(prompt)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        isSubmitting = false,
                        error = mapErrorToUserMessage(e, "Failed to submit bug report"),
                    )
                )
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
            clientModeStreamStartedAtElapsedMs
        if (clientModeStreamInFlight && elapsedSinceClientModeStart in 0..750) {
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
            clientModeStreamJob?.cancel(CancellationException("User interrupted active run"))
            clientModeStreamInFlight = false
            clientModeStreamStartedAtElapsedMs = 0L
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

    fun loadProjectBrief() {
        if (projectContext == null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectBrief = _uiState.value.projectBrief.copy(isLoading = true, error = null)
            )
            try {
                val blocks = blockRepository.getBlocks(agentId)
                _uiState.value = _uiState.value.copy(
                    projectBrief = ProjectBriefUiState(
                        isLoading = false,
                        sections = buildProjectBriefSections(blocks).toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isLoading = false,
                        error = mapErrorToUserMessage(e, "Failed to load project brief"),
                    )
                )
            }
        }
    }

    fun saveProjectBriefSection(
        key: ProjectBriefSectionKey,
        content: String,
    ) {
        val existingSection = _uiState.value.projectBrief.sections[key] ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectBrief = _uiState.value.projectBrief.copy(isSaving = true, error = null)
            )
            try {
                val updatedBlock = blockRepository.updateAgentBlock(
                    agentId = agentId,
                    blockLabel = existingSection.blockLabel,
                    params = BlockUpdateParams(value = content),
                )
                val updatedSection = existingSection.copy(
                    content = updatedBlock.value,
                    updatedAt = updatedBlock.updatedAt,
                )
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isSaving = false,
                        sections = (_uiState.value.projectBrief.sections + (key to updatedSection))
                            .toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isSaving = false,
                        error = mapErrorToUserMessage(e, "Failed to save project brief"),
                    )
                )
            }
        }
    }

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
        clientModeStreamJob?.cancel()
        // letta-mobile-5s1n (regression fix): mark stream in flight BEFORE
        // launching, so observer emissions inside the launch (which run
        // synchronously on UnconfinedTestDispatcher / immediate main) see
        // the flag even before `clientModeStreamJob` is assigned.
        clientModeStreamInFlight = true
        clientModeStreamStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        // Fresh Client Mode bootstrap can spend noticeable time creating the
        // blank Letta conversation before a timeline append or gateway chunk
        // happens. Surface the run as streaming immediately so the UI shows
        // the thinking affordance and a second composer submit is routed as a
        // steering/stop action instead of starting a new send that cancels the
        // in-flight bootstrap. Keep the conversation state unchanged until a
        // real conversation id exists.
        _uiState.value = _uiState.value.copy(
            isLoadingMessages = false,
            isLoadingOlderMessages = false,
            hasMoreOlderMessages = false,
            isStreaming = true,
            isAgentTyping = true,
            error = null,
        )
        val startedAt = java.time.Instant.now().toString()
        val userMessageId = "client-user-${java.util.UUID.randomUUID()}"
        val assistantMessageId = "client-assistant-${java.util.UUID.randomUUID()}"
        resetPreConversationClientModeBuffer()
        pendingClientModeStreamSessionId = assistantMessageId
        val outboundText = buildClientModeOutboundText(text, attachments)
        // letta-mobile-c87t: when entering an existing-conversation route under
        // Client Mode, prefer the route's conversationId arg so the gateway can
        // resumeSession() into the matching Letta conversation. Fall back to
        // the saved-state-handle pointer for cases where Client Mode set up the
        // conversation itself (fresh-route entry continued in-place).
        val initialPriorConversationId = explicitConversationId ?: currentClientModeConversationId()
        // letta-mobile-w4pp: do NOT pre-create an empty Letta conversation
        // for fresh Client Mode routes. Field logs from Pixel 9 Pro showed
        // the gateway rejecting sends into those app-created empty
        // conversations with BAD_MESSAGE / `Missing "type" field`. Fresh
        // routes pass conversationId=null, let the gateway allocate the real
        // conversation, then migrate the quarantined user echo plus buffered
        // assistant chunks into the timeline when the first chunk/result echoes
        // that conversation id.
        composerController.clearAfterSend()
        clientModeStreamJob = viewModelScope.launch {
            val priorConversationId = initialPriorConversationId
            // letta-mobile-c87t (PR 2): when we already know the
            // conversationId, append the user bubble through the timeline so
            // the SSE-side reconcile + fuzzy matcher (PR 1) can collapse it
            // against the Letta-persisted echo. This activates the dormant
            // CLIENT_MODE_HARNESS source path, gives 8cm8 the telemetry it
            // needs, and removes the user-bubble dual-write that Meridian
            // flagged.
            //
            // Truly fresh Client Mode sends now pre-create a blank Letta
            // conversation above, so the normal timeline-backed path is used
            // immediately. The in-memory branch remains only as a legacy
            // fallback for callers that somehow still have no conversationId.
            currentConversationTracker.setCurrent(priorConversationId)
            if (priorConversationId != null) {
                // Bind this Client Mode conversation so the timeline becomes
                // the source of truth for the message list.
                val convId = priorConversationId
                // letta-mobile-5s1n (regression fix): set the streaming flags
                // BEFORE appending to the timeline. The observer flow re-emits
                // synchronously on every timeline state change and reads
                // `prev.isStreaming` to decide whether to preserve the in-flight
                // signal (see startTimelineObserver). If we wrote `true` after
                // the append, the observer would race ahead with the old
                // `false`, clobber it, and the spinner would never show.
                _uiState.value = _uiState.value.copy(
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                    conversationState = ConversationState.Ready(convId),
                )
                runCatching {
                    timelineRepository.appendClientModeLocal(
                        conversationId = convId,
                        content = text,
                        attachments = attachments,
                    )
                }.onFailure { e ->
                    android.util.Log.w(
                        "AdminChatViewModel",
                        "appendClientModeLocal failed; continuing without legacy in-memory fallback",
                        e,
                    )
                }
                // Ensure observer is running so subsequent timeline state
                // emissions (and the SSE-driven Confirmed echoes) reach the
                // UI. Idempotent for the same conversationId.
                startTimelineObserver(convId)
            } else {
                // Fresh-route: no conversationId yet; expose only a quarantined
                // optimistic USER echo until the gateway echoes one, then
                // append it to the timeline and clear this bootstrap state.
                stopTimelineObserver()
                pendingClientModeBootstrapUserMessage = UiMessage(
                    id = userMessageId,
                    role = "user",
                    content = text,
                    timestamp = startedAt,
                    attachments = attachments.map {
                        com.letta.mobile.data.model.UiImageAttachment(
                            base64 = it.base64,
                            mediaType = it.mediaType,
                        )
                    },
                )
                _uiState.value = _uiState.value.copy(
                    messages = pendingClientModeBootstrapMessages(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                    conversationState = ConversationState.NoConversation,
                )
            }

            var latestConversationId = priorConversationId
            // letta-mobile-c87t: track whether the gateway substituted a different
            // conversation for the one we requested (recovery path). On the first
            // chunk that carries a non-null conversationId, compare against
            // priorConversationId; if it differs, emit a banner + propagate the
            // new ID to the nav saved-state-handle so a back-then-re-enter doesn't
            // try to resume the dead requested ID again.
            var swapEvaluated = false
            var accumulatedAssistantPreview = ""
            // letta-mobile-hf93: track whether the gateway ever sent any
            // user-visible payload (text, reasoning, or a tool event) for
            // this turn. If the stream terminates with no payload — e.g. a
            // gateway/upstream-agent error that produced only a result
            // frame — we surface an error instead of silently flashing
            // the typing indicator.
            var sawAssistantPayload = false
            // letta-mobile-5s1n / yoic.2.5: one-shot guard for migrating the
            // quarantined fresh-route USER echo into the timeline. Known-conv
            // sends skip migration because the bubble was appended up-front.
            var migratedToTimeline = priorConversationId != null
            try {
                clientModeChatSender.streamMessage(
                    screenAgentId = agentId,
                    text = outboundText,
                    conversationId = priorConversationId,
                ).collect { chunk ->
                    chunk.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        latestConversationId = conversationId

                        val isTextPayload = !chunk.text.isNullOrEmpty() &&
                            chunk.event != BotStreamEvent.REASONING &&
                            chunk.event != BotStreamEvent.TOOL_CALL &&
                            chunk.event != BotStreamEvent.TOOL_RESULT

                        if (isTextPayload) {
                            accumulatedAssistantPreview += chunk.text.orEmpty()
                            submitClientModeNotificationCandidate(
                                conversationId = conversationId,
                                messageId = assistantMessageId,
                                previewText = accumulatedAssistantPreview,
                                phase = NotificationCandidatePhase.Partial,
                                isFinal = false,
                            )
                        }
                        if (!swapEvaluated) {
                            swapEvaluated = true
                            // Substitution detected: gateway opened a fresh conversation
                            // because our requested one was unrecoverable. Surface the
                            // banner and update the nav arg so subsequent renavigations
                            // pick up the new ID.
                            if (priorConversationId != null && priorConversationId != conversationId) {
                                _uiState.update { state ->
                                    state.copy(
                                        clientModeConversationSwap = ClientModeConversationSwap(
                                            requestedConversationId = priorConversationId,
                                            newConversationId = conversationId,
                                        ),
                                    )
                                }
                                savedStateHandle["conversationId"] = conversationId

                                // letta-mobile-flk.5: when the gateway swaps
                                // conversations mid-stream (recovery from a
                                // stuck/unrecoverable conv — typically
                                // pending requires_approval), the user's
                                // optimistic Local was appended to the OLD
                                // conv's timeline (or is still the quarantined
                                // fresh-route bootstrap echo). The assistant
                                // chunks about to flow on this same stream
                                // belong to the NEW conv on the Letta server.
                                // If we leave the observer pointed at the OLD
                                // conv we'll keep writing assistant Locals
                                // there and the user sees nothing in the
                                // conv they're now navigated to.
                                //
                                // Migrate the user bubble to the new conv,
                                // clear the bootstrap echo (the new conv's
                                // timeline is now the authority), and re-point
                                // the observer. Subsequent
                                // chunks already use latestConversationId
                                // for their write target via
                                // handleClientModeStreamChunk.
                                runCatching {
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = conversationId,
                                        content = text,
                                        attachments = attachments,
                                    )
                                    clearPendingClientModeBootstrapUserMessage()
                                    setClientModeConversationId(conversationId)
                                    clientModeBootstrapState = ClientModeBootstrapState.Ready(conversationId)
                                    currentConversationTracker.setCurrent(conversationId)
                                    startTimelineObserver(conversationId)
                                    // Mark the legacy fresh-route migration
                                    // as already done so the block below
                                    // (which assumes priorConversationId ==
                                    // null) doesn't double-migrate.
                                    migratedToTimeline = true
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Conversation swap migration to new conv timeline failed; staying on old conv",
                                        e,
                                    )
                                }
                            }
                        }
                    }
                    if (!latestConversationId.isNullOrBlank()) {
                        setClientModeConversationId(latestConversationId)
                        activeConversationId = latestConversationId
                        clientModeBootstrapState = ClientModeBootstrapState.Ready(latestConversationId)
                        currentConversationTracker.setCurrent(latestConversationId)
                        // letta-mobile-5s1n: fresh-route migration. The first
                        // time the gateway echoes a conversationId for a fresh
                        // send, we move the optimistic user bubble into the
                        // timeline (where assistant streaming will also write,
                        // via handleClientModeStreamChunkViaTimeline) and start
                        // the timeline observer so the UI sees Local + assistant
                        // content uniformly. Idempotent — guarded by
                        // `migratedToTimeline`.
                        if (priorConversationId == null && !migratedToTimeline) {
                            migratedToTimeline = true
                            val newConvId = latestConversationId
                            if (newConvId != null) {
                                runCatching {
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = newConvId,
                                        content = text,
                                        attachments = attachments,
                                    )
                                    replayPreConversationClientModeBuffer(
                                        conversationId = newConvId,
                                        assistantMessageId = assistantMessageId,
                                    )
                                    // Drop the bootstrap user echo; the
                                    // observer will render it plus replayed
                                    // assistant chunks as timeline Locals.
                                    clearPendingClientModeBootstrapUserMessage()
                                    startTimelineObserver(newConvId)
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Fresh-route migration to timeline failed; keeping bootstrap user echo only",
                                        e,
                                    )
                                }
                            }
                        }
                    }

                    // letta-mobile-hf93: count any payload-bearing chunk —
                    // text, reasoning event, tool call/result — as an
                    // assistant payload. Empty terminal frames don't count.
                    if (chunkCarriesAssistantPayload(chunk)) {
                        sawAssistantPayload = true
                    }

                    if (!chunk.done) {
                        handleClientModeStreamChunk(
                            chunk = chunk,
                            assistantMessageId = assistantMessageId,
                            timestamp = startedAt,
                            conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                        )
                        return@collect
                    }

                    handleClientModeStreamChunk(
                        chunk = chunk,
                        assistantMessageId = assistantMessageId,
                        timestamp = startedAt,
                        replaceAssistant = true,
                        conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                    )

                    latestConversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        if (accumulatedAssistantPreview.isNotBlank()) {
                            submitClientModeNotificationCandidate(
                                conversationId = conversationId,
                                messageId = assistantMessageId,
                                previewText = accumulatedAssistantPreview,
                                phase = NotificationCandidatePhase.Final,
                                isFinal = true,
                            )
                        }
                    }

                    // letta-mobile-hf93: a stream that completed without
                    // any payload would otherwise look identical to a
                    // dropped network round-trip — typing indicator
                    // briefly flashes, then nothing. Surface an explicit
                    // error so the user knows the round-trip happened.
                    val terminalError = if (!sawAssistantPayload && !chunk.aborted) {
                        "Agent returned no content. Try again or check the agent's status."
                    } else {
                        null
                    }

                    val prevState = _uiState.value
                    _uiState.value = collapseCompletedRunsIfStreamingFinished(
                        previous = prevState,
                        next = prevState.copy(
                            conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                                ?: ConversationState.NoConversation,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = terminalError ?: prevState.error,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                        ?: _uiState.value.conversationState,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "sendViaClientMode: stream exception", e)
                _uiState.value = _uiState.value.copy(
                    conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                        ?: ConversationState.NoConversation,
                    error = e.message ?: "Client Mode send failed",
                    isStreaming = false,
                    isAgentTyping = false,
                )
            } finally {
                resetPreConversationClientModeBuffer()
                clientModeStreamInFlight = false
                clientModeStreamStartedAtElapsedMs = 0L
                refreshContextWindow()
                if (clientModeStreamJob?.isCancelled != false) {
                    clientModeStreamJob = null
                }
            }
        }
    }

    private fun resetPreConversationClientModeBuffer() {
        pendingClientModeStreamSessionId = null
        pendingClientModeStreamChunks.clear()
    }

    private fun bufferPreConversationClientModeChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
    ) {
        if (chunk.done && !chunkCarriesAssistantPayload(chunk)) return
        if (pendingClientModeStreamSessionId != assistantMessageId) {
            pendingClientModeStreamSessionId = assistantMessageId
            pendingClientModeStreamChunks.clear()
        }
        if (pendingClientModeStreamChunks.size >= MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS) {
            pendingClientModeStreamChunks.removeFirst()
            Telemetry.event(
                "AdminChatVM", "clientMode.preConversationBuffer.dropOldest",
                "assistantMessageId" to assistantMessageId,
                "maxChunks" to MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS,
                level = Telemetry.Level.WARN,
            )
        }
        pendingClientModeStreamChunks.addLast(chunk)
        Telemetry.event(
            "AdminChatVM", "clientMode.preConversationChunkBuffered",
            "assistantMessageId" to assistantMessageId,
            "event" to (chunk.event?.name ?: "null"),
            "hasText" to (chunk.text != null),
            "bufferedChunks" to pendingClientModeStreamChunks.size,
        )
    }

    private suspend fun replayPreConversationClientModeBuffer(
        conversationId: String,
        assistantMessageId: String,
    ) {
        if (pendingClientModeStreamSessionId != assistantMessageId) return
        if (pendingClientModeStreamChunks.isEmpty()) return
        val bufferedChunks = pendingClientModeStreamChunks.toList()
        var replayed = 0
        bufferedChunks.forEach { bufferedChunk ->
            runCatching {
                timelineRepository.upsertClientModeStreamChunk(
                    conversationId = conversationId,
                    chunk = bufferedChunk.toTimelineStreamChunk(),
                    assistantMessageId = assistantMessageId,
                )
            }.onSuccess {
                replayed++
            }.onFailure {
                logTimelineUpsertFailure(
                    t = it,
                    kind = bufferedChunk.event?.name ?: "ASSISTANT",
                    localId = assistantMessageId,
                )
            }
        }
        resetPreConversationClientModeBuffer()
        Telemetry.event(
            "AdminChatVM", "clientMode.preConversationBufferReplayed",
            "conversationId" to conversationId,
            "assistantMessageId" to assistantMessageId,
            "replayedChunks" to replayed,
            "droppedChunks" to (bufferedChunks.size - replayed),
        )
    }

    private fun handleClientModeStreamChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean = false,
        conversationId: String? = null,
    ) {
        if (conversationId != null) {
            handleClientModeStreamChunkViaTimeline(
                chunk = chunk,
                conversationId = conversationId,
                assistantMessageId = assistantMessageId,
                timestamp = timestamp,
                replaceAssistant = replaceAssistant,
            )
            return
        }
        bufferPreConversationClientModeChunk(
            chunk = chunk,
            assistantMessageId = assistantMessageId,
        )
    }

    /**
     * Route a Client Mode assistant/reasoning/tool stream chunk through the
     * TimelineRepository reducer. The timeline observer is the only normal
     * read path for known-conversation Client Mode messages.
     */
    private fun handleClientModeStreamChunkViaTimeline(
        chunk: BotStreamChunk,
        conversationId: String,
        assistantMessageId: String,
        @Suppress("UNUSED_PARAMETER") timestamp: String,
        @Suppress("UNUSED_PARAMETER") replaceAssistant: Boolean,
    ) {
        viewModelScope.launch {
            runCatching {
                timelineRepository.upsertClientModeStreamChunk(
                    conversationId = conversationId,
                    chunk = chunk.toTimelineStreamChunk(),
                    assistantMessageId = assistantMessageId,
                )
            }.onFailure {
                logTimelineUpsertFailure(
                    t = it,
                    kind = chunk.event?.name ?: "ASSISTANT",
                    localId = assistantMessageId,
                )
            }
        }
    }

    /**
     * letta-mobile-hf93: a chunk "carries assistant payload" when it has
     * any user-visible content — non-empty text, a tool call/result, or
     * (eventually) a non-empty reasoning frame. The terminal `done=true`
     * frame on its own does NOT count, since WsBotClient emits one for
     * every stream regardless of whether the upstream agent produced
     * output.
     */
    private fun chunkCarriesAssistantPayload(chunk: BotStreamChunk): Boolean {
        if (!chunk.text.isNullOrEmpty()) return true
        return when (chunk.event) {
            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            BotStreamEvent.REASONING -> true
            // letta-mobile-flk.5: CONVERSATION_SWAP is a control-plane
            // signal, not user-visible content — must not gate the
            // hf93 "no-payload" terminal-error path.
            BotStreamEvent.CONVERSATION_SWAP,
            BotStreamEvent.ASSISTANT, null -> false
        }
    }

    private fun submitClientModeNotificationCandidate(
        conversationId: String,
        messageId: String,
        previewText: String,
        phase: NotificationCandidatePhase,
        isFinal: Boolean,
    ) {
        notificationDeliveryCoordinator.submit(
            NotificationDeliveryCandidate(
                conversationId = conversationId,
                agentId = agentId,
                agentName = _uiState.value.agentName,
                conversationSummary = null,
                messageId = messageId,
                runId = null,
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = phase,
                previewText = previewText,
                isFinal = isFinal,
            ),
        )
    }

    private fun logTimelineUpsertFailure(t: Throwable, kind: String, localId: String) {
        android.util.Log.w(
            "AdminChatViewModel",
            "Client Mode timeline upsert failed (kind=$kind, localId=$localId)",
            t,
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

private val projectBriefLabelAliases = mapOf(
    ProjectBriefSectionKey.Description to listOf(
        "project_description",
        "project-description",
        "project description",
        "description",
        "brief_description",
    ),
    ProjectBriefSectionKey.KeyDecisions to listOf(
        "key_decisions",
        "key-decisions",
        "key decisions",
        "decisions",
        "project_decisions",
    ),
    ProjectBriefSectionKey.TechStack to listOf(
        "tech_stack",
        "tech-stack",
        "tech stack",
        "stack",
        "technology_stack",
    ),
    ProjectBriefSectionKey.ActiveGoals to listOf(
        "active_goals",
        "active-goals",
        "active goals",
        "goals",
        "current_goals",
    ),
    ProjectBriefSectionKey.RecentChanges to listOf(
        "recent_changes",
        "recent-changes",
        "recent changes",
        "changes",
        "latest_changes",
    ),
)

private fun buildProjectBriefSections(blocks: List<Block>): Map<ProjectBriefSectionKey, ProjectBriefSection> {
    return ProjectBriefSectionKey.entries.mapNotNull { key ->
        val block = blocks.firstOrNull { candidate ->
            val canonical = candidate.label?.canonicalBriefLabel() ?: return@firstOrNull false
            projectBriefLabelAliases.getValue(key).any { alias ->
                canonical == alias.canonicalBriefLabel()
            }
        } ?: return@mapNotNull null

        key to ProjectBriefSection(
            key = key,
            blockLabel = block.label ?: return@mapNotNull null,
            content = block.value,
            updatedAt = block.updatedAt,
        )
    }.toMap()
}

private fun String.canonicalBriefLabel(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

private fun buildClientModeLocationPrompt(path: String): String = """
Please switch your active working directory to:

$path

Use this as the cwd/base path for subsequent filesystem and shell tool calls in this conversation. After switching, briefly confirm the current working directory.
""".trim()

private fun BotStreamChunk.toTimelineStreamChunk(): com.letta.mobile.data.timeline.ClientModeStreamChunk =
    com.letta.mobile.data.timeline.ClientModeStreamChunk(
        event = when (event) {
            BotStreamEvent.REASONING -> com.letta.mobile.data.timeline.ClientModeStreamEvent.REASONING
            BotStreamEvent.TOOL_CALL -> com.letta.mobile.data.timeline.ClientModeStreamEvent.TOOL_CALL
            BotStreamEvent.TOOL_RESULT -> com.letta.mobile.data.timeline.ClientModeStreamEvent.TOOL_RESULT
            BotStreamEvent.ASSISTANT -> com.letta.mobile.data.timeline.ClientModeStreamEvent.ASSISTANT
            BotStreamEvent.CONVERSATION_SWAP, null -> null
        },
        text = text,
        uuid = uuid,
        toolCallId = toolCallId,
        toolName = toolName,
        toolInput = toolInput?.toString(),
        toolCalls = toolCalls.orEmpty(),
        isError = isError,
        done = done,
    )

/**
 * Client Mode rides through lettabot's WebSocket gateway, whose Matrix/Tuwunel
 * client already supports multimodal input by JSON-serializing Letta-native
 * MessageContentItem[] into the text `content` frame and parsing it back on the
 * gateway. Mirror that wire contract here: text-only messages stay plain text;
 * image sends become `[text?, image...]` using Letta's native base64 image part
 * schema.
 */
private fun buildClientModeOutboundText(
    text: String,
    attachments: List<MessageContentPart.Image>,
): String = if (attachments.isEmpty()) {
    text
} else {
    buildContentParts(text, attachments).toJsonArray().toString()
}

private fun buildBugReportPrompt(draft: ProjectBugReportDraft): String {
    val title = draft.title.trim()
    val description = draft.description.trim()
    val tags = draft.tags.joinToString(", ").ifBlank { "none" }
    val attachments = draft.attachmentReferences.joinToString("\n") { "- $it" }
        .ifBlank { "- none" }

    return buildString {
        appendLine("Bug Report: $title")
        appendLine("Severity: ${draft.severity.wireValue}")
        appendLine("Tags: $tags")
        appendLine("Description:")
        appendLine(description)
        appendLine()
        appendLine("Attached media references:")
        appendLine(attachments)
        appendLine()
        append("Please triage this issue, decide whether to create/update beads, and route it to the appropriate coding agent if needed.")
    }.trim()

}
