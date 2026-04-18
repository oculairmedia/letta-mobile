package com.letta.mobile.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ProjectBugReport
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
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
    val projectBrief: ProjectBriefUiState = ProjectBriefUiState(),
    val bugReports: ProjectBugReportUiState = ProjectBugReportUiState(),
    val projectAgents: ProjectAgentsUiState = ProjectAgentsUiState(),
    /**
     * Pending image attachments staged in the composer. Reset on successful send.
     * Cap enforced by [MAX_COMPOSER_ATTACHMENTS].
     */
    val pendingAttachments: ImmutableList<com.letta.mobile.data.model.MessageContentPart.Image> =
        persistentListOf(),
)

/** Upper bound on composer image attachments per message. */
const val MAX_COMPOSER_ATTACHMENTS = 4

/** Upper bound on per-message total base64 payload (approximate — ~8 MB). */
const val MAX_COMPOSER_TOTAL_BYTES = 8 * 1024 * 1024

@HiltViewModel
class AdminChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
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
) : ViewModel() {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
    }

    val agentId: String = savedStateHandle.get<String>("agentId")!!
    private val initialMessage: String? = savedStateHandle.get<String>("initialMessage")
    val scrollToMessageId: String? = savedStateHandle.get<String>("scrollToMessageId")
    private val activeConversationId: String?
        get() = conversationManager.getActiveConversationId(agentId)
    val conversationId: String? get() = activeConversationId
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

    private var timelineObserverJob: kotlinx.coroutines.Job? = null

    init {
        savedStateHandle.get<String>("conversationId")
            ?.takeIf { it.isNotBlank() }
            ?.let { conversationManager.setActiveConversation(agentId, it) }
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
            resolveConversationAndLoad()
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
                if (activeConversationId == null) {
                    val resolvedConversationId = conversationManager.resolveAndSetActiveConversation(
                        agentId = agentId,
                        maxAgeMs = CONVERSATION_CACHE_TTL_MS,
                    )
                    if (resolvedConversationId != null) {
                        android.util.Log.d("AdminChatViewModel", "Resolved to most recent conversation: $resolvedConversationId")
                    }
                }

                val conversationId = activeConversationId
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
        val requestedConversationId = activeConversationId
        if (requestedConversationId == null) {
            if (requestedConversationId == activeConversationId) {
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
            if (requestedConversationId == activeConversationId) {
                _uiState.value = _uiState.value.copy(
                    agentName = cachedAgent?.name ?: _uiState.value.agentName,
                    messages = if (cachedMessages.isNotEmpty()) cachedMessages.toUiMessages().toImmutableList() else _uiState.value.messages,
                    isLoadingMessages = cachedMessages.isEmpty(),
                    error = null,
                )
            }
        } else {
            if (requestedConversationId == activeConversationId) {
                _uiState.value = _uiState.value.copy(isLoadingMessages = true)
            }
        }
        try {
            val agent = agentRepository.getAgent(agentId).first()
            if (requestedConversationId != activeConversationId) {
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
            if (requestedConversationId != activeConversationId) {
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
        if (activeConversationId == null) {
            resolveConversationAndLoad()
            return
        }
        viewModelScope.launch { loadMessagesInternal() }
    }

    fun retryConversationLoad() {
        resolveConversationAndLoad()
    }

    fun loadOlderMessages() {
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

                val mergedMessages = mergeOlderMessages(
                    olderMessages = olderMessages.toUiMessages(),
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

        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to "timeline",
            "length" to text.length,
            "attachments" to attachments.size,
        )
        sendMessageViaTimeline(text, attachments)
    }

    fun reportComposerError(message: String) {
        _uiState.value = _uiState.value.copy(composerError = message)
    }

    fun clearComposerError() {
        if (_uiState.value.composerError == null) return
        _uiState.value = _uiState.value.copy(composerError = null)
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
     * Idempotent — calling repeatedly for the same conversation is a no-op.
     */
    private fun startTimelineObserver(conversationId: String) {
        if (timelineObserverJob?.isActive == true) return
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
            val hydrateSignal = viewModelScope.launch {
                loop.events.collect { ev ->
                    when (ev) {
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.Hydrated,
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.HydrateFailed -> {
                            _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                        }
                        else -> Unit
                    }
                }
            }

            try {
                flow.collect { timeline ->
                    val ui = timeline.events.mapNotNull { it.toUiMessageOrNull() }.toImmutableList()
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
                    _uiState.value = _uiState.value.copy(
                        messages = ui,
                        isLoadingMessages = if (clearLoading) false
                                           else _uiState.value.isLoadingMessages,
                        isStreaming = anyLocalPending,
                        isAgentTyping = anyLocalPending && !tailIsAssistant,
                    )
                }
            } finally {
                hydrateSignal.cancel()
            }
        }
    }

    /** Convert a [TimelineEvent] to a [UiMessage] for display. */
    private fun com.letta.mobile.data.timeline.TimelineEvent.toUiMessageOrNull(): UiMessage? {
        return when (val ev = this) {
            is com.letta.mobile.data.timeline.TimelineEvent.Local -> UiMessage(
                id = ev.otid,
                role = when (ev.role) {
                    com.letta.mobile.data.timeline.Role.USER -> "user"
                    com.letta.mobile.data.timeline.Role.ASSISTANT -> "assistant"
                    com.letta.mobile.data.timeline.Role.SYSTEM -> "system"
                },
                content = ev.content,
                timestamp = ev.sentAt.toString(),
                isPending = ev.deliveryState == com.letta.mobile.data.timeline.DeliveryState.SENDING,
                attachments = ev.attachments.map {
                    com.letta.mobile.data.model.UiImageAttachment(
                        base64 = it.base64,
                        mediaType = it.mediaType,
                    )
                },
            )
            is com.letta.mobile.data.timeline.TimelineEvent.Confirmed -> {
                val role = when (ev.messageType) {
                    com.letta.mobile.data.timeline.TimelineMessageType.USER -> "user"
                    com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT -> "assistant"
                    com.letta.mobile.data.timeline.TimelineMessageType.REASONING -> "assistant"
                    com.letta.mobile.data.timeline.TimelineMessageType.SYSTEM -> "system"
                    com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL,
                    com.letta.mobile.data.timeline.TimelineMessageType.TOOL_RETURN,
                    com.letta.mobile.data.timeline.TimelineMessageType.OTHER -> return null
                }
                UiMessage(
                    id = ev.serverId,
                    role = role,
                    content = ev.content,
                    timestamp = ev.date.toString(),
                    isPending = false,
                    isReasoning = ev.messageType == com.letta.mobile.data.timeline.TimelineMessageType.REASONING,
                    attachments = ev.attachments.map {
                        com.letta.mobile.data.model.UiImageAttachment(
                            base64 = it.base64,
                            mediaType = it.mediaType,
                        )
                    },
                )
            }
        }
    }

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
