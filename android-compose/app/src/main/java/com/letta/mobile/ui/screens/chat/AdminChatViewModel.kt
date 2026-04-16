package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotChatRequest
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
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    val sections: Map<ProjectBriefSectionKey, ProjectBriefSection> = emptyMap(),
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
    val tags: List<String> = emptyList(),
    val attachmentReferences: List<String> = emptyList(),
)

@androidx.compose.runtime.Immutable
data class ProjectBugReportUiState(
    val isSubmitting: Boolean = false,
    val recentReports: List<ProjectBugReport> = emptyList(),
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
    val agents: List<ProjectAgentActivity> = emptyList(),
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
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val activeApprovalRequestId: String? = null,
    val projectBrief: ProjectBriefUiState = ProjectBriefUiState(),
    val bugReports: ProjectBugReportUiState = ProjectBugReportUiState(),
    val projectAgents: ProjectAgentsUiState = ProjectAgentsUiState(),
)

@HiltViewModel
class AdminChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val bugReportRepository: BugReportRepository,
    private val folderRepository: FolderRepository,
    private val conversationManager: ConversationManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val botGateway: BotGateway,
    private val botConfigStore: BotConfigStore,
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
                        agents = activities,
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
                    bugReports = _uiState.value.bugReports.copy(recentReports = recent, error = null)
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
                        recentReports = listOf(logged) + _uiState.value.bugReports.recentReports
                            .filterNot { it.id == logged.id }
                            .take(4),
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
                        sections = buildProjectBriefSections(blocks),
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
                        sections = _uiState.value.projectBrief.sections + (key to updatedSection),
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
            return
        }
        val cachedAgent = agentRepository.getCachedAgent(agentId)
        val cachedMessages = messageRepository.getCachedMessages(requestedConversationId)
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
            val targetMessageId = scrollToMessageId
            val (agent, fetchedMessages) = supervisorScope {
                val agentDeferred = async { agentRepository.getAgent(agentId).first() }
                val messagesDeferred = async {
                    messageRepository.fetchMessages(
                        agentId = agentId,
                        conversationId = requestedConversationId,
                        targetMessageId = targetMessageId,
                    )
                }
                agentDeferred.await() to messagesDeferred.await()
            }
            if (requestedConversationId != activeConversationId) {
                return
            }
            _uiState.value = _uiState.value.copy(
                agentName = agent.name,
                conversationState = ConversationState.Ready(requestedConversationId),
            )
            val messages = messageRepository.getCachedMessages(requestedConversationId).toUiMessages()
            if (messages.isNotEmpty()) hasSummary = true
            _uiState.value = _uiState.value.copy(
                messages = messages.toImmutableList(),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = targetMessageId != null || fetchedMessages.size >= MessageRepository.INITIAL_FETCH_LIMIT,
            )

            // Background sync disabled - was causing UI flashes
        } catch (e: Exception) {
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

    /**
     * Refresh on resume - always fetches from server for admin monitoring.
     * Shows cached messages immediately to avoid flash, then updates with server data.
     */
    fun refreshFromCache() {
        val conversationId = activeConversationId
        if (conversationId == null) {
            // Conversation not resolved yet - init will handle it
            return
        }
        
        // Show cached messages immediately (no loading indicator)
        val cachedMessages = messageRepository.getCachedMessages(conversationId).toUiMessages()
        if (cachedMessages.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                messages = cachedMessages.toImmutableList()
            )
        }
        
        // Always fetch fresh from server - this is an admin surface that needs real-time data
        viewModelScope.launch {
            try {
                // Check for new messages since last sync (incremental)
                val newMessages = messageRepository.checkForNewMessages(
                    agentId = agentId,
                    conversationId = conversationId,
                )
                
                // Update UI with all messages from cache (which now includes new ones)
                val allMessages = messageRepository.getCachedMessages(conversationId).toUiMessages()
                if (allMessages.isNotEmpty() && conversationId == activeConversationId) {
                    _uiState.value = _uiState.value.copy(
                        messages = allMessages.toImmutableList()
                    )
                }
            } catch (e: Exception) {
                // Silent fail - we already showed cached
            }
        }
    }
    
    /**
     * Start polling for new messages. Call when entering the chat screen.
     * Polls every 3 seconds for new messages from the server.
     */
    private var pollingJob: kotlinx.coroutines.Job? = null
    
    fun startMessagePolling() {
        val conversationId = activeConversationId ?: return
        
        // Don't start if already polling
        if (pollingJob?.isActive == true) return
        
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(3000) // Poll every 3 seconds
                
                // Skip polling while streaming (we get real-time updates via SSE)
                if (_uiState.value.isStreaming) continue
                
                try {
                    val newMessages = messageRepository.checkForNewMessages(
                        agentId = agentId,
                        conversationId = conversationId,
                    )
                    
                    if (newMessages.isNotEmpty() && conversationId == activeConversationId) {
                        val allMessages = messageRepository.getCachedMessages(conversationId).toUiMessages()
                        _uiState.value = _uiState.value.copy(
                            messages = allMessages.toImmutableList()
                        )
                    }
                } catch (e: Exception) {
                    // Silent fail for background polling
                }
            }
        }
    }
    
    fun stopMessagePolling() {
        pollingJob?.cancel()
        pollingJob = null
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

        viewModelScope.launch {
            val userMessage = UiMessage(
                id = "pending-${System.currentTimeMillis()}",
                role = "user",
                content = text,
                timestamp = java.time.Instant.now().toString(),
                isPending = true,
            )
            _inputText.value = ""
            val existingMessages = (_uiState.value.messages + userMessage).toImmutableList()
            _uiState.value = _uiState.value.copy(
                messages = existingMessages,
                isStreaming = true,
                isAgentTyping = true,
            )
            try {
                // Auto-create conversation if none exists
                var convId = activeConversationId
                if (convId == null) {
                    try {
                        val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                        convId = conversationManager.createAndSetActiveConversation(agentId, summary)
                        hasSummary = true
                        _uiState.value = _uiState.value.copy(
                            conversationState = ConversationState.Ready(convId),
                        )
                        android.util.Log.d("AdminChatViewModel", "Created new conversation: $convId")
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(
                            error = "Failed to create conversation: ${e.message}",
                            isStreaming = false,
                            isAgentTyping = false
                        )
                        return@launch
                    }
                } else if (!hasSummary) {
                    try {
                        val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                        conversationRepository.updateConversation(convId, agentId, summary)
                        hasSummary = true
                    } catch (e: Exception) {
                        android.util.Log.w("AdminChatViewModel", "Failed to set conversation summary", e)
                    }
                }
                val stream = if (projectContext != null) {
                    sendProjectMessageViaGateway(text = text, conversationId = convId)
                } else {
                    messageRepository.sendMessage(agentId, text, convId)
                }
                stream.collect { state ->
                    when (state) {
                        is StreamState.Sending -> {
                            _uiState.value = _uiState.value.copy(isAgentTyping = true)
                        }
                        is StreamState.Streaming -> {
                            val newMessages = state.messages.toUiMessages()
                            _uiState.value = _uiState.value.copy(
                                messages = (existingMessages + newMessages).toImmutableList(),
                                isStreaming = true,
                                isAgentTyping = false,
                            )
                        }
                        is StreamState.ToolExecution -> {
                            val toolCall = PendingToolCall(id = state.toolName, name = state.toolName)
                            pendingToolsMap[state.toolName] = toolCall
                            _uiState.value = _uiState.value.copy(
                                isAgentTyping = true,
                                pendingTools = pendingToolsMap.values.toImmutableList(),
                            )
                        }
                        is StreamState.Complete -> {
                            pendingToolsMap.clear()
                            val newMessages = state.messages.toUiMessages()
                            _uiState.value = _uiState.value.copy(
                                messages = (existingMessages + newMessages).toImmutableList(),
                                isStreaming = false,
                                isAgentTyping = false,
                                pendingTools = persistentListOf(),
                            )
                            // Don't reload after streaming - streamed messages are already displayed
                            // Reload was causing flash by replacing UI state
                            if (projectContext != null) {
                                loadProjectAgents()
                                loadProjectBrief()
                            }
                        }
                        is StreamState.Error -> {
                            _uiState.value = _uiState.value.copy(error = state.message, isStreaming = false, isAgentTyping = false)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isStreaming = false, isAgentTyping = false)
            }
        }
    }

    private fun sendProjectMessageViaGateway(
        text: String,
        conversationId: String,
    ) = kotlinx.coroutines.flow.flow {
        emit(StreamState.Sending)

        ensureProjectGatewaySession()

        val response = internalBotClient.sendMessage(
            BotChatRequest(
                message = text,
                channelId = "in_app",
                chatId = projectContext?.identifier,
                senderId = "mobile_user",
                senderName = projectContext?.name,
                agentId = agentId,
                conversationId = conversationId,
            )
        )

        response.conversationId
            ?.takeIf { it.isNotBlank() && it != activeConversationId }
            ?.let { conversationManager.setActiveConversation(agentId, it) }

        val assistantMessage = AppMessage(
            id = "bot-${System.currentTimeMillis()}",
            date = java.time.Instant.now(),
            messageType = MessageType.ASSISTANT,
            content = response.response,
        )
        emit(StreamState.Complete(listOf(assistantMessage)))
        loadProjectAgents()
        loadProjectBrief()
    }

    private suspend fun ensureProjectGatewaySession() {
        if (botGateway.getSession(agentId) != null) return

        val enabledConfigs = botConfigStore.getAll().filter { it.enabled }
        val matchingConfig = enabledConfigs.firstOrNull { it.agentId == agentId }
            ?: throw IllegalStateException(
                "Project chat requires an enabled embedded bot for this agent. Configure it in Bot Settings first."
            )

        botGateway.start(enabledConfigs)

        if (botGateway.getSession(matchingConfig.agentId) == null) {
            throw IllegalStateException(
                "The embedded bot gateway could not start a session for this project agent. Check Bot Settings."
            )
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

    private suspend fun reloadMessagesFromServer(conversationId: String) {
        // Small delay to let server persist the messages we just streamed
        kotlinx.coroutines.delay(500)
        try {
            messageRepository.fetchMessages(
                agentId = agentId,
                conversationId = conversationId,
                targetMessageId = scrollToMessageId,
            )
            if (conversationId != activeConversationId) {
                return
            }
            // Server state is truth — just display it
            val serverMessages = messageRepository.getCachedMessages(conversationId).toUiMessages()
            if (serverMessages.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(messages = serverMessages.toImmutableList())
            }
        } catch (e: Exception) {
            android.util.Log.w("AdminChatViewModel", "Silent reload failed", e)
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
