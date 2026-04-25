package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationManager
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ProjectChatContext(
    val identifier: String,
    val name: String,
    val lettaFolderId: String? = null,
    val filesystemPath: String? = null,
    val gitUrl: String? = null,
    val lastSyncAt: String? = null,
    val activeCodingAgents: String? = null,
)

@androidx.compose.runtime.Immutable
data class PendingToolCall(
    val id: String,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
)

enum class ProjectBriefSectionKey {
    Description,
    KeyDecisions,
    TechStack,
    ActiveGoals,
    RecentChanges,
}

@androidx.compose.runtime.Immutable
data class ProjectBriefSection(
    val key: ProjectBriefSectionKey,
    val blockLabel: String,
    val content: String,
    val updatedAt: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectBriefUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    // ImmutableMap so Compose treats this whole state as stable — raw
    // kotlin.collections.Map is an unstable interface type to the
    // compiler (it could be a MutableMap at runtime). See o7ob.2.6.
    val sections: kotlinx.collections.immutable.ImmutableMap<ProjectBriefSectionKey, ProjectBriefSection> =
        kotlinx.collections.immutable.persistentMapOf(),
    val error: String? = null,
)

enum class BugSeverity(val wireValue: String) {
    Critical("critical"),
    High("high"),
    Medium("medium"),
    Low("low"),
}

@androidx.compose.runtime.Immutable
data class ProjectBugReportDraft(
    val title: String = "",
    val description: String = "",
    val severity: BugSeverity = BugSeverity.Medium,
    val tags: ImmutableList<String> = persistentListOf(),
    val attachmentReferences: ImmutableList<String> = persistentListOf(),
)

@androidx.compose.runtime.Immutable
data class ProjectBugReportUiState(
    val isSubmitting: Boolean = false,
    val recentReports: ImmutableList<ProjectBugReport> = persistentListOf(),
    val lastSubmittedPrompt: String? = null,
    val error: String? = null,
)

enum class ProjectAgentStatusTone {
    Neutral,
    Good,
    Busy,
    Error,
}

@androidx.compose.runtime.Immutable
data class ProjectAgentActivity(
    val id: String,
    val name: String,
    val statusLabel: String,
    val statusTone: ProjectAgentStatusTone,
    val detail: String? = null,
    val model: String? = null,
    val lastActivity: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectAgentsUiState(
    val isLoading: Boolean = false,
    val agents: ImmutableList<ProjectAgentActivity> = persistentListOf(),
    val error: String? = null,
)

sealed interface ConversationState {
    @androidx.compose.runtime.Immutable
    data object Loading : ConversationState

    @androidx.compose.runtime.Immutable
    data class Ready(val conversationId: String) : ConversationState

    @androidx.compose.runtime.Immutable
    data object NoConversation : ConversationState

    @androidx.compose.runtime.Immutable
    data class Error(val message: String) : ConversationState
}

@androidx.compose.runtime.Immutable
data class ChatUiState(
    val conversationState: ConversationState = ConversationState.Loading,
    val messages: ImmutableList<UiMessage> = persistentListOf(),
    val isLoadingMessages: Boolean = true,
    val isLoadingOlderMessages: Boolean = false,
    val hasMoreOlderMessages: Boolean = false,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: ImmutableList<PendingToolCall> = persistentListOf(),
    val agentName: String = "",
    val error: String? = null,
    val composerError: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val activeApprovalRequestId: String? = null,
    val collapsedRunIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val expandedReasoningMessageIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val projectBrief: ProjectBriefUiState = ProjectBriefUiState(),
    val bugReports: ProjectBugReportUiState = ProjectBugReportUiState(),
    val projectAgents: ProjectAgentsUiState = ProjectAgentsUiState(),
    /**
     * Pending image attachments staged in the composer. Reset on successful send.
     * Cap enforced by [MAX_COMPOSER_ATTACHMENTS].
     */
    val pendingAttachments: ImmutableList<com.letta.mobile.data.model.MessageContentPart.Image> =
        persistentListOf(),
    /**
     * Surfaced when the LettaBot harness substituted a fresh conversation ID for
     * the one we requested (i.e. our requested conv was unrecoverable on the
     * gateway/SDK side, gateway opened a new conversation and reported it back
     * via session_init). The original Letta-server timeline rows for the prior
     * conversation remain visible; new client-mode turns persist under the new
     * conversation. Dismissable. See `letta-mobile-c87t`.
     */
    val clientModeConversationSwap: ClientModeConversationSwap? = null,
)

/**
 * Banner state for the gateway's conversation-substitution recovery path.
 * Emitted by `AdminChatViewModel` when `session_init.conversation_id` differs
 * from the conversation we asked the gateway to resume.
 */
data class ClientModeConversationSwap(
    val requestedConversationId: String,
    val newConversationId: String,
)

/** Upper bound on composer image attachments per message. */
const val MAX_COMPOSER_ATTACHMENTS = 4

/** Upper bound on per-message total base64 payload (approximate — ~8 MB). */
const val MAX_COMPOSER_TOTAL_BYTES = 8 * 1024 * 1024

@HiltViewModel
class AdminChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val timelineRepository: com.letta.mobile.data.timeline.TimelineRepository,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val bugReportRepository: BugReportRepository,
    private val folderRepository: FolderRepository,
    private val conversationManager: ConversationManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val internalBotClient: InternalBotClient,
    private val clientModeChatSender: ClientModeChatSender,
    private val currentConversationTracker: com.letta.mobile.channel.CurrentConversationTracker,
) : ViewModel() {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        private const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        private const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
        private const val CLIENT_MODE_CONVERSATION_ID_KEY = "clientModeConversationId"
    }

    val agentId: String = savedStateHandle.get<String>("agentId")!!
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
    private val activeConversationId: String?
        get() = conversationManager.getActiveConversationId(agentId)
    private val clientModeEnabled: StateFlow<Boolean> = settingsRepository.observeClientModeEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
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

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * letta-mobile-23h5 (regression fix 2026-04-19): older messages fetched via
     * pagination need to survive the next timeline observer emission (which
     * overwrites `_uiState.messages` with the live timeline contents). Hold
     * a per-conversation prefix here, scoped to a (conversationId → list)
     * pair so a conversation switch resets the prefix automatically.
     *
     * The timeline observer concatenates `olderMessagesPrefix` ahead of its
     * mapped events on every emission; the loader replaces this list when a
     * new page arrives.
     */
    private var olderMessagesPrefix: Pair<String, List<UiMessage>> = "" to emptyList()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val chatBackground: StateFlow<ChatBackground> = settingsRepository.getChatBackgroundKey()
        .map { ChatBackground.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackground.Default)

    val chatFontScale: StateFlow<Float> = settingsRepository.getChatFontScale()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

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

    private fun collapsedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(COLLAPSED_RUN_IDS_KEY)?.toSet().orEmpty()

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

    fun toggleRunCollapsed(runId: String) {
        val next = collapsedRunIds().toMutableSet().apply {
            if (!add(runId)) remove(runId)
        }
        persistCollapsedRunIds(next)
    }

    fun toggleReasoningExpanded(messageId: String) {
        val next = expandedReasoningMessageIds().toMutableSet().apply {
            if (!add(messageId)) remove(messageId)
        }
        persistExpandedReasoningMessageIds(next)
    }

    private var timelineObserverJob: kotlinx.coroutines.Job? = null
    private var timelineHydrateSignalJob: kotlinx.coroutines.Job? = null
    private var clientModeStreamJob: Job? = null
    private var clientModeMessages: List<UiMessage> = emptyList()
    // Conversation id the current observer job is bound to. Needed so we can
    // detect "same conversation, already observing" vs "user switched convs
    // and we must rebind" — fixing letta-mobile-nw2e, where the previous
    // `isActive == true` guard silently ignored conversation switches.
    private var timelineObserverConversationId: String? = null

    init {
        requestedConversationArg
            ?.takeIf { it.isNotBlank() }
            ?.let { conversationManager.setActiveConversation(agentId, it) }
        if (isFreshRoute) {
            setClientModeConversationId(null)
            currentConversationTracker.setCurrent(null)
        }
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
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
                            _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    isAgentTyping = false,
                                    error = null,
                                )
                            }
                        }
                        resolveConversationAndLoad()
                    }
            }
            if (projectContext != null) {
                loadProjectAgents()
                loadProjectBrief()
                loadRecentBugReports()
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
                val activities = buildProjectAgentActivities(project)
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

    fun tryHandleSlashCommand(text: String): Boolean {
        if (projectContext == null) return false
        return when (text.trim()) {
            "/bug" -> true
            else -> false
        }
    }

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

    private fun resolveConversationAndLoad() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.Loading,
                isLoadingMessages = true,
                error = null,
            )

            try {
                if (shouldUseClientModeForCurrentRoute) {
                    // letta-mobile-c87t (PR 2): when we have a Client Mode
                    // conversationId, route through the timeline observer so
                    // SSE-side persisted messages flow into the UI alongside
                    // the optimistic CLIENT_MODE_HARNESS Locals from
                    // appendClientModeLocal. Without this the chat would only
                    // ever show in-memory bubbles and history wouldn't load.
                    //
                    // Resolution order: explicit nav arg → saved-state-handle
                    // pointer. Fresh routes that haven't sent yet have neither
                    // and stay on the in-memory path until the gateway
                    // provides one (see sendMessageViaClientMode migration).
                    val clientConversationId = explicitConversationId
                        ?: currentClientModeConversationId()
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
                        // Fresh route, no conv yet — keep in-memory path.
                        stopTimelineObserver()
                        _uiState.value = _uiState.value.copy(
                            agentName = agent?.name ?: _uiState.value.agentName,
                            conversationState = ConversationState.NoConversation,
                            messages = clientModeMessages.toImmutableList(),
                            isLoadingMessages = false,
                            isLoadingOlderMessages = false,
                            hasMoreOlderMessages = false,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = null,
                        )
                    }
                    initialMessage?.let { message ->
                        if (message.isNotBlank()) {
                            sendMessage(message)
                        }
                    }
                    return@launch
                }

                if (activeConversationId == null && explicitConversationId == null) {
                    val resolvedConversationId = conversationManager.resolveAndSetActiveConversation(
                        agentId = agentId,
                        maxAgeMs = CONVERSATION_CACHE_TTL_MS,
                    )
                }

                val conversationId = activeConversationId ?: explicitConversationId?.also {
                    conversationManager.setActiveConversation(agentId, it)
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

                initialMessage?.let { message ->
                    if (message.isNotBlank()) {
                        sendMessage(message)
                    }
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

    private suspend fun loadMessagesInternal() {
        val loadTimer = Telemetry.startTimer("AdminChatVM", "loadMessages")
        val requestedConversationId = activeConversationId ?: explicitConversationId?.also {
            conversationManager.setActiveConversation(agentId, it)
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
                val (prevConv, prevPrefix) = olderMessagesPrefix
                val basePrefix = if (prevConv == conversationId) prevPrefix else emptyList()
                val seenIds = HashSet<String>(basePrefix.size + olderUi.size)
                val grown = ArrayList<UiMessage>(basePrefix.size + olderUi.size)
                for (m in olderUi) if (seenIds.add(m.id)) grown.add(m)
                for (m in basePrefix) if (seenIds.add(m.id)) grown.add(m)
                olderMessagesPrefix = conversationId to grown
                val mergedMessages = mergeOlderMessages(
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

        val attachments = _uiState.value.pendingAttachments.toList()
        if (text.isBlank() && attachments.isEmpty()) return

        val isClientMode = shouldUseClientModeForCurrentRoute
        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to if (isClientMode) "client_mode" else "timeline",
            "length" to text.length,
            "attachments" to attachments.size,
        )
        if (isClientMode) {
            if (attachments.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    composerError = "Client Mode attachments are not supported yet",
                )
                return
            }
            sendMessageViaClientMode(text)
            return
        }
        sendMessageViaTimeline(text, attachments)
    }

    private fun sendMessageViaClientMode(text: String) {
        clientModeStreamJob?.cancel()
        clientModeStreamJob = viewModelScope.launch {
            val startedAt = java.time.Instant.now().toString()
            val userMessageId = "client-user-${System.currentTimeMillis()}"
            val assistantMessageId = "client-assistant-${System.currentTimeMillis()}"
            // letta-mobile-c87t: when entering an existing-conversation route under
            // Client Mode, prefer the route's conversationId arg so the gateway can
            // resumeSession() into the matching Letta conversation. Fall back to
            // the saved-state-handle pointer for cases where Client Mode set up the
            // conversation itself (fresh-route entry continued in-place).
            val priorConversationId = explicitConversationId ?: currentClientModeConversationId()
            // Force-fresh only when the user genuinely entered a fresh-route nav
            // (no conversation arg AND no in-flight saved-state pointer). For
            // existing-route entries we want resume, not new.
            val forceFreshConversation = isFreshRoute && priorConversationId == null
            // letta-mobile-c87t (PR 2): when we already know the
            // conversationId, append the user bubble through the timeline so
            // the SSE-side reconcile + fuzzy matcher (PR 1) can collapse it
            // against the Letta-persisted echo. This activates the dormant
            // CLIENT_MODE_HARNESS source path, gives 8cm8 the telemetry it
            // needs, and removes the user-bubble dual-write that Meridian
            // flagged.
            //
            // Fresh-route sends still use the in-memory path until the
            // gateway returns a conversationId on the first chunk; at that
            // point we migrate the user bubble into the timeline (see below).
            currentConversationTracker.setCurrent(priorConversationId)
            _inputText.value = ""
            if (priorConversationId != null) {
                // Stop any prior observer for a different conversation, then
                // start one for this Client Mode conversation so the timeline
                // becomes the source of truth for the message list.
                val convId = priorConversationId
                if (timelineObserverConversationId != convId) {
                    stopTimelineObserver()
                }
                runCatching {
                    timelineRepository.appendClientModeLocal(
                        conversationId = convId,
                        content = text,
                    )
                }.onFailure { e ->
                    android.util.Log.w(
                        "AdminChatViewModel",
                        "appendClientModeLocal failed; falling back to in-memory bubble",
                        e,
                    )
                    // Fall back to the in-memory path so the user still sees
                    // their bubble even if the timeline append fails.
                    val fallback = clientModeMessages + UiMessage(
                        id = userMessageId,
                        role = "user",
                        content = text,
                        timestamp = startedAt,
                    )
                    clientModeMessages = fallback
                    _uiState.value = _uiState.value.copy(messages = fallback.toImmutableList())
                }
                // Ensure observer is running so subsequent timeline state
                // emissions (and the SSE-driven Confirmed echoes) reach the
                // UI. Idempotent for the same conversationId.
                startTimelineObserver(convId)
                _uiState.value = _uiState.value.copy(
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    composerError = null,
                    error = null,
                    pendingAttachments = persistentListOf(),
                    conversationState = ConversationState.Ready(convId),
                )
            } else {
                // Fresh-route: no conversationId yet; keep the in-memory path
                // until the gateway echoes one, then migrate.
                stopTimelineObserver()
                val baseMessages = clientModeMessages
                val nextMessages = baseMessages + UiMessage(
                    id = userMessageId,
                    role = "user",
                    content = text,
                    timestamp = startedAt,
                )
                clientModeMessages = nextMessages
                _uiState.value = _uiState.value.copy(
                    messages = nextMessages.toImmutableList(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    composerError = null,
                    error = null,
                    pendingAttachments = persistentListOf(),
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
            // letta-mobile-5s1n: one-shot guard for the fresh-route migration
            // from in-memory clientModeMessages → timeline. Already-known-conv
            // sends skip migration (the bubble was appended to the timeline
            // up-front in the priorConversationId-non-null branch above).
            var migratedToTimeline = priorConversationId != null
            try {
                clientModeChatSender.streamMessage(
                    screenAgentId = agentId,
                    text = text,
                    conversationId = priorConversationId,
                    forceFreshConversation = forceFreshConversation,
                ).collect { chunk ->
                    chunk.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        latestConversationId = conversationId
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
                            }
                        }
                    }
                    if (!latestConversationId.isNullOrBlank()) {
                        setClientModeConversationId(latestConversationId)
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
                                    )
                                    // Drop the in-memory user bubble; the
                                    // observer will render it as a Local.
                                    clientModeMessages = clientModeMessages
                                        .filterNot { it.id == userMessageId }
                                    startTimelineObserver(newConvId)
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Fresh-route migration to timeline failed; staying in-memory",
                                        e,
                                    )
                                }
                            }
                        }
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

                    _uiState.value = _uiState.value.copy(
                        conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                            ?: ConversationState.NoConversation,
                        isStreaming = false,
                        isAgentTyping = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                        ?: ConversationState.NoConversation,
                    error = e.message ?: "Client Mode send failed",
                    isStreaming = false,
                    isAgentTyping = false,
                )
            } finally {
                if (clientModeStreamJob?.isCancelled != false) {
                    clientModeStreamJob = null
                }
            }
        }
    }

    private fun handleClientModeStreamChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean = false,
        // letta-mobile-5s1n: when known, all assistant streaming flows through
        // timelineRepository.upsertClientModeLocalAssistantChunk so the
        // timeline is the single source of truth. Pre-conversationId chunks
        // (rare; only the first chunk of a fresh-route send) keep the legacy
        // in-memory path until the gateway echoes a conversationId.
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
        // Fresh-route, conversationId not yet known: keep the in-memory path
        // for this single chunk. The next chunk will carry conversationId
        // (gateway always echoes by chunk #1 in practice) and we'll migrate.
        handleClientModeStreamChunkLegacy(
            chunk = chunk,
            assistantMessageId = assistantMessageId,
            timestamp = timestamp,
            replaceAssistant = replaceAssistant,
        )
    }

    /**
     * letta-mobile-5s1n: route a Client Mode assistant stream chunk through
     * the timeline. Idempotent across repeat chunks for the same logical
     * event (same localId), so calling repeatedly grows a single bubble
     * rather than appending duplicates.
     */
    private fun handleClientModeStreamChunkViaTimeline(
        chunk: BotStreamChunk,
        conversationId: String,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean,
    ) {
        val sentAt = runCatching { java.time.Instant.parse(timestamp) }
            .getOrDefault(java.time.Instant.now())
        when (chunk.event) {
            BotStreamEvent.REASONING -> {
                val localId = "cm-reason-${chunk.uuid ?: assistantMessageId}"
                val delta = chunk.text.orEmpty()
                viewModelScope.launch {
                    runCatching {
                        timelineRepository.upsertClientModeLocalAssistantChunk(
                            conversationId = conversationId,
                            localId = localId,
                            build = {
                                com.letta.mobile.data.timeline.TimelineEvent.Local(
                                    position = 0.0, // upsert assigns nextLocalPosition
                                    otid = localId,
                                    content = "",
                                    role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                                    sentAt = sentAt,
                                    deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.REASONING,
                                    reasoningContent = delta,
                                )
                            },
                            transform = { existing ->
                                existing.copy(
                                    reasoningContent = if (replaceAssistant) delta
                                    else (existing.reasoningContent.orEmpty() + delta),
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.REASONING,
                                )
                            },
                        )
                    }.onFailure { logTimelineUpsertFailure(it, "REASONING", localId) }
                }
            }

            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            -> {
                val toolCallId = chunk.toolCallId ?: chunk.uuid ?: return
                val localId = "cm-tool-$toolCallId"
                val toolName = chunk.toolName ?: "tool"
                val arguments = chunk.toolInput?.toString().orEmpty()
                val isResult = chunk.event == BotStreamEvent.TOOL_RESULT
                val resultText: String? = if (isResult) chunk.text else null
                val resultIsError: Boolean = isResult && chunk.isError
                viewModelScope.launch {
                    runCatching {
                        timelineRepository.upsertClientModeLocalAssistantChunk(
                            conversationId = conversationId,
                            localId = localId,
                            build = {
                                com.letta.mobile.data.timeline.TimelineEvent.Local(
                                    position = 0.0,
                                    otid = localId,
                                    content = "",
                                    role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                                    sentAt = sentAt,
                                    deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL,
                                    toolCalls = listOf(
                                        com.letta.mobile.data.model.ToolCall(
                                            id = toolCallId,
                                            name = toolName,
                                            arguments = arguments,
                                        ),
                                    ),
                                    toolReturnContent = resultText,
                                    toolReturnIsError = resultIsError,
                                )
                            },
                            transform = { existing ->
                                val existingTool = existing.toolCalls.firstOrNull()
                                val mergedTool = com.letta.mobile.data.model.ToolCall(
                                    id = existingTool?.id ?: toolCallId,
                                    name = existingTool?.name ?: toolName,
                                    arguments = arguments.ifBlank { existingTool?.arguments.orEmpty() },
                                )
                                existing.copy(
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL,
                                    toolCalls = listOf(mergedTool),
                                    toolReturnContent = resultText ?: existing.toolReturnContent,
                                    toolReturnIsError = if (isResult) resultIsError else existing.toolReturnIsError,
                                )
                            },
                        )
                    }.onFailure { logTimelineUpsertFailure(it, "TOOL", localId) }
                }
            }

            else -> {
                val delta = chunk.text?.takeIf { it.isNotEmpty() } ?: return
                val localId = "cm-assist-$assistantMessageId"
                viewModelScope.launch {
                    runCatching {
                        timelineRepository.upsertClientModeLocalAssistantChunk(
                            conversationId = conversationId,
                            localId = localId,
                            build = {
                                com.letta.mobile.data.timeline.TimelineEvent.Local(
                                    position = 0.0,
                                    otid = localId,
                                    content = delta,
                                    role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                                    sentAt = sentAt,
                                    deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT,
                                )
                            },
                            transform = { existing ->
                                existing.copy(
                                    content = if (replaceAssistant) delta
                                    else (existing.content + delta),
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT,
                                )
                            },
                        )
                    }.onFailure { logTimelineUpsertFailure(it, "ASSISTANT", localId) }
                }
            }
        }
    }

    private fun logTimelineUpsertFailure(t: Throwable, kind: String, localId: String) {
        android.util.Log.w(
            "AdminChatViewModel",
            "Client Mode timeline upsert failed (kind=$kind, localId=$localId)",
            t,
        )
    }

    /**
     * Pre-conversationId fallback path. Used only for chunks that arrive
     * before the gateway echoes a conversationId (typically only the very
     * first chunk of a fresh-route send). Once a conversationId is known
     * the rest of the stream flows through the timeline.
     */
    private fun handleClientModeStreamChunkLegacy(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean,
    ) {
        when (chunk.event) {
            BotStreamEvent.REASONING -> {
                val messageId = chunk.uuid ?: "client-reasoning-${System.currentTimeMillis()}"
                upsertClientModeMessage(
                    messageId = messageId,
                    timestamp = timestamp,
                ) { existing ->
                    (existing ?: UiMessage(
                        id = messageId,
                        role = "assistant",
                        content = "",
                        timestamp = timestamp,
                        isReasoning = true,
                    )).copy(
                        content = if (!replaceAssistant && existing != null) {
                            existing.content + chunk.text.orEmpty()
                        } else {
                            chunk.text.orEmpty()
                        },
                        isReasoning = true,
                    )
                }
            }

            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            -> {
                val toolCallId = chunk.toolCallId ?: chunk.uuid ?: return
                val messageId = "client-tool-$toolCallId"
                val toolName = chunk.toolName ?: "tool"
                val arguments = chunk.toolInput?.toString().orEmpty()
                upsertClientModeMessage(
                    messageId = messageId,
                    timestamp = timestamp,
                ) { existing ->
                    val existingTool = existing?.toolCalls?.firstOrNull()
                    val result = when (chunk.event) {
                        BotStreamEvent.TOOL_RESULT -> chunk.text ?: existingTool?.result
                        else -> existingTool?.result
                    }
                    val status = when (chunk.event) {
                        BotStreamEvent.TOOL_RESULT -> if (chunk.isError) "error" else "success"
                        else -> existingTool?.status
                    }
                    UiMessage(
                        id = messageId,
                        role = "assistant",
                        content = "",
                        timestamp = existing?.timestamp ?: timestamp,
                        toolCalls = listOf(
                            UiToolCall(
                                name = toolName,
                                arguments = arguments.ifBlank { existingTool?.arguments.orEmpty() },
                                result = result,
                                status = status,
                            )
                        ),
                    )
                }
            }

            else -> {
                chunk.text?.takeIf { it.isNotEmpty() }?.let { delta ->
                    upsertClientModeAssistantMessage(
                        messageId = assistantMessageId,
                        timestamp = timestamp,
                        content = delta,
                        append = !replaceAssistant,
                    )
                }
            }
        }
    }

    private fun upsertClientModeAssistantMessage(
        messageId: String,
        timestamp: String,
        content: String,
        append: Boolean,
    ) {
        upsertClientModeMessage(
            messageId = messageId,
            timestamp = timestamp,
        ) { existing ->
            if (existing != null) {
                existing.copy(
                    content = if (append) existing.content + content else content,
                )
            } else {
                UiMessage(
                    id = messageId,
                    role = "assistant",
                    content = content,
                    timestamp = timestamp,
                )
            }
        }
    }

    private fun upsertClientModeMessage(
        messageId: String,
        timestamp: String,
        build: (UiMessage?) -> UiMessage,
    ) {
        val currentMessages = clientModeMessages.toMutableList()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        val existing = currentMessages.getOrNull(index)
        val next = build(existing)
        if (index >= 0) {
            currentMessages[index] = next.copy(timestamp = existing?.timestamp ?: timestamp)
        } else {
            currentMessages += next.copy(timestamp = next.timestamp.ifBlank { timestamp })
        }
        clientModeMessages = currentMessages
        if (clientModeEnabled.value) {
            _uiState.value = _uiState.value.copy(messages = currentMessages.toImmutableList())
        }
    }

    private fun currentClientModeConversationId(): String? =
        savedStateHandle.get<String>(CLIENT_MODE_CONVERSATION_ID_KEY)?.takeIf { it.isNotBlank() }

    private fun setClientModeConversationId(conversationId: String?) {
        savedStateHandle[CLIENT_MODE_CONVERSATION_ID_KEY] = conversationId?.takeIf { it.isNotBlank() }
    }

    private fun stopTimelineObserver() {
        timelineObserverJob?.cancel()
        timelineObserverJob = null
        timelineHydrateSignalJob?.cancel()
        timelineHydrateSignalJob = null
        timelineObserverConversationId = null
    }

    fun reportComposerError(message: String) {
        _uiState.value = _uiState.value.copy(composerError = message)
    }

    fun clearComposerError() {
        if (_uiState.value.composerError == null) return
        _uiState.value = _uiState.value.copy(composerError = null)
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

    /**
     * Stage a new image attachment in the composer. Enforces [MAX_COMPOSER_ATTACHMENTS]
     * count cap and [MAX_COMPOSER_TOTAL_BYTES] cumulative base64 size cap. Returns
     * true on success; false if a cap was hit (caller should surface the error).
     */
    fun addAttachment(image: com.letta.mobile.data.model.MessageContentPart.Image): Boolean {
        val current = _uiState.value.pendingAttachments
        if (current.size >= MAX_COMPOSER_ATTACHMENTS) {
            Telemetry.event(
                "AdminChatVM",
                "attachment.rejected",
                "reason" to "count_cap",
                "current" to current.size,
                "max" to MAX_COMPOSER_ATTACHMENTS,
            )
            reportComposerError("Attachment limit reached ($MAX_COMPOSER_ATTACHMENTS max).")
            return false
        }
        val newTotal = current.sumOf { it.base64.length } + image.base64.length
        if (newTotal > MAX_COMPOSER_TOTAL_BYTES) {
            Telemetry.event(
                "AdminChatVM",
                "attachment.rejected",
                "reason" to "size_cap",
                "newTotal" to newTotal,
                "max" to MAX_COMPOSER_TOTAL_BYTES,
            )
            reportComposerError("Attachments too large — downscale or remove some before sending.")
            return false
        }
        _uiState.value = _uiState.value.copy(
            pendingAttachments = (current + image).toPersistentList(),
            composerError = null,
        )
        Telemetry.event(
            "AdminChatVM",
            "attachment.added",
            "size" to image.base64.length,
            "mediaType" to image.mediaType,
            "totalCount" to (current.size + 1),
        )
        return true
    }

    /** Remove a staged attachment by index. */
    fun removeAttachment(index: Int) {
        val current = _uiState.value.pendingAttachments
        if (index !in current.indices) return
        _uiState.value = _uiState.value.copy(
            pendingAttachments = (current.toMutableList().also { it.removeAt(index) })
                .toPersistentList(),
        )
        Telemetry.event(
            "AdminChatVM",
            "attachment.removed",
            "index" to index,
        )
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
            _inputText.value = ""
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                composerError = null,
                // Clear composer attachments optimistically; they're carried on the
                // Local event now so the user bubble still shows them.
                pendingAttachments = persistentListOf(),
            )
            try {
                var convId = activeConversationId
                if (convId == null) {
                    val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                    convId = conversationManager.createAndSetActiveConversation(agentId, summary)
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
        if (timelineObserverConversationId == conversationId &&
            timelineObserverJob?.isActive == true
        ) {
            return
        }
        // Conversation switch (or first bind): tear down any in-flight
        // subscriptions from the previous conversation before starting new
        // ones. Without this the hydrate-signal job would leak on every
        // switch (it was a local `val` in the old impl, never cancellable).
        timelineObserverJob?.cancel()
        timelineHydrateSignalJob?.cancel()
        // letta-mobile-23h5 (regression fix 2026-04-19): drop any backfill
        // prefix from the previous conversation so it cannot bleed into the
        // new conversation's history.
        olderMessagesPrefix = "" to emptyList()
        timelineObserverConversationId = conversationId
        timelineObserverJob = viewModelScope.launch {
            val flow = try {
                timelineRepository.observe(conversationId)
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "Timeline observe failed", e)
                _uiState.value = _uiState.value.copy(error = "Timeline init failed: ${e.message}")
                return@launch
            }
            // Also subscribe to the loop's sync events so we can definitively
            // clear the loading spinner on Hydrated (even for empty convs).
            val loop = timelineRepository.getOrCreate(conversationId)
            currentConversationTracker.setCurrent(conversationId)
            timelineHydrateSignalJob = viewModelScope.launch {
                loop.events.collect { ev ->
                    when (ev) {
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.Hydrated -> {
                            android.util.Log.i(
                                "AdminChatViewModel",
                                "Timeline ready conv=$conversationId count=${ev.messageCount}",
                            )
                            _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                        }
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.HydrateFailed -> {
                            _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                        }
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.ReconcileError -> {
                            // letta-mobile-j44j: reconcile's GET /messages can
                            // fail after a successful stream (e.g. network blip
                            // after the last SSE frame). TimelineSyncLoop already
                            // retries transient errors internally; by the time
                            // this event reaches us the retry was exhausted.
                            //
                            // The assistant reply has already appeared in the
                            // timeline (Confirmed events arrived during SSE),
                            // so we don't clear messages — we just tell the
                            // user the post-stream sync didn't finish so they
                            // know to pull-to-refresh if the bubble looks off.
                            // Also clear the streaming/typing indicators so the
                            // UI doesn't stay stuck pretending we're still
                            // waiting on the server.
                            _uiState.value = _uiState.value.copy(
                                error = "Couldn't sync agent reply — pull to refresh",
                                isStreaming = false,
                                isAgentTyping = false,
                            )
                        }
                        else -> Unit
                    }
                }
            }

            try {
                flow.collect { timeline ->
                    val live = timeline.events.mapNotNull { it.toUiMessageOrNull() }
                    // Prepend any backfilled older pages for THIS conversation.
                    val (prefixConv, prefixList) = olderMessagesPrefix
                    val prefix = if (prefixConv == conversationId) prefixList else emptyList()
                    val seenIds = HashSet<String>(live.size + prefix.size)
                    val combined = ArrayList<UiMessage>(live.size + prefix.size)
                    for (m in prefix) if (seenIds.add(m.id)) combined.add(m)
                    for (m in live) if (seenIds.add(m.id)) combined.add(m)
                    val ui = combined.toImmutableList()
                    val tailIsAssistant = timeline.events.lastOrNull().let {
                        it is com.letta.mobile.data.timeline.TimelineEvent.Confirmed &&
                            it.messageType == com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT
                    }
                    val anyLocalPending = timeline.events.any {
                        it is com.letta.mobile.data.timeline.TimelineEvent.Local &&
                            it.deliveryState == com.letta.mobile.data.timeline.DeliveryState.SENDING
                    }
                    // Any non-empty emission also implies hydrate succeeded.
                    val clearLoading = ui.isNotEmpty()
                    // letta-mobile-23h5 (regression fix 2026-04-19): the
                    // timeline source-of-truth path was never flipping
                    // hasMoreOlderMessages to true, so ChatScreen's scroll
                    // detector (`!state.hasMoreOlderMessages → return false`)
                    // never fired loadOlderMessages. Optimistically assume
                    // there's history to fetch any time we have at least one
                    // confirmed message; the first page fetch corrects this
                    // (sets it back to false when fewer than PAGE_SIZE rows
                    // come back).
                    val anyConfirmed = ui.any { !it.isPending }
                    // Optimistically allow the scroll detector to call
                    // loadOlderMessages once we have any confirmed history
                    // — the loader settles the truth (sets back to false
                    // when fewer than PAGE_SIZE rows come back).
                    val newHasMoreOlder = if (anyConfirmed) true
                                          else _uiState.value.hasMoreOlderMessages
                    _uiState.value = _uiState.value.copy(
                        messages = ui,
                        isLoadingMessages = if (clearLoading) false
                                           else _uiState.value.isLoadingMessages,
                        isStreaming = anyLocalPending,
                        isAgentTyping = anyLocalPending && !tailIsAssistant,
                        hasMoreOlderMessages = newHasMoreOlder,
                    )
                }
            } finally {
                // Tear down the sibling hydrate-signal collector when the
                // main observer terminates (conv switch, scope cancel, etc.).
                // Field-based reference replaces the prior local `val hydrateSignal`
                // so the job is also cancellable from `startTimelineObserver`
                // on conversation switch — see letta-mobile-nw2e.
                timelineHydrateSignalJob?.cancel()
            }
        }
    }

    /** Convert a [TimelineEvent] to a [UiMessage] for display. */
    internal fun com.letta.mobile.data.timeline.TimelineEvent.toUiMessageOrNull(): UiMessage? =
        timelineEventToUiMessage(this)

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    ) {
        viewModelScope.launch {
            val convId = activeConversationId
            if (convId == null) {
                _uiState.value = _uiState.value.copy(error = "No active conversation available for approval")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )

            try {
                messageRepository.submitApproval(
                    conversationId = convId,
                    approvalRequestId = requestId,
                    toolCallIds = toolCallIds,
                    approve = approve,
                    reason = reason,
                )
                // Don't reload - approval response will come via streaming
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to submit approval",
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = null,
                )
            }
        }
    }

    private fun mergeOlderMessages(
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> {
        if (olderMessages.isEmpty()) return existingMessages

        val existingIds = existingMessages.mapTo(mutableSetOf()) { it.id }
        return olderMessages.filterNot { it.id in existingIds } + existingMessages
    }

    fun resetMessages() {
        if (shouldUseClientModeForCurrentRoute) {
            setClientModeConversationId(null)
            clientModeMessages = emptyList()
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
        _inputText.value = text
    }

    val canSendMessages: Boolean
        get() = when (_uiState.value.conversationState) {
            ConversationState.Loading -> false
            is ConversationState.Error -> false
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> true
        }

    private suspend fun buildProjectAgentActivities(
        project: ProjectChatContext,
    ): List<ProjectAgentActivity> {
        val liveStatuses = runCatching { internalBotClient.listAgents() }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        val folderId = project.lettaFolderId
        if (folderId.isNullOrBlank()) {
            return liveStatuses[agentId]
                ?.let { listOf(it.toProjectAgentActivity(agent = null)) }
                ?: emptyList()
        }

        agentRepository.refreshAgentsIfStale(maxAgeMs = 60_000)
        val folderAgentIds = folderRepository.listAgentsForFolder(folderId)
        val agents = folderAgentIds.mapNotNull { id ->
            agentRepository.getCachedAgent(id)
                ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()
        }

        return agents.map { agent ->
            liveStatuses[agent.id].toProjectAgentActivity(agent)
        }.sortedWith(compareBy<ProjectAgentActivity> { it.statusLabel }.thenBy { it.name.lowercase() })
    }

    private fun BotAgentInfo?.toProjectAgentActivity(agent: Agent?): ProjectAgentActivity {
        val rawStatus = this?.status?.lowercase()
        val (statusLabel, statusTone) = when {
            rawStatus == null && agent?.lastRunCompletion != null -> "Idle" to ProjectAgentStatusTone.Neutral
            rawStatus == null -> "Disconnected" to ProjectAgentStatusTone.Neutral
            rawStatus.contains("error") || rawStatus.contains("fail") -> "Error" to ProjectAgentStatusTone.Error
            rawStatus.contains("working") || rawStatus.contains("running") || rawStatus.contains("busy") -> "Working" to ProjectAgentStatusTone.Busy
            rawStatus.contains("connected") || rawStatus.contains("ready") || rawStatus.contains("idle") -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Good
            else -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Neutral
        }

        val metadataWork = agent?.metadata?.get("current_work")?.toString()?.trim('"')
        val detail = metadataWork
            ?: agent?.lastRunCompletion
            ?: if (this != null) "Embedded bot session available" else null

        return ProjectAgentActivity(
            id = agent?.id ?: this?.id.orEmpty(),
            name = agent?.name ?: this?.name.orEmpty(),
            statusLabel = statusLabel,
            statusTone = statusTone,
            detail = detail,
            model = agent?.model,
            lastActivity = agent?.updatedAt ?: agent?.createdAt,
        )
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
