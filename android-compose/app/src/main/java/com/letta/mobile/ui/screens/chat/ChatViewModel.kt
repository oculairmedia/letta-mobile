package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.ui.theme.ChatBackground
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class PendingToolCall(
    val id: String,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
)

data class ChatUiState(
    val messages: ImmutableList<UiMessage> = persistentListOf(),
    val isLoadingMessages: Boolean = true,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: ImmutableList<PendingToolCall> = persistentListOf(),
    val agentName: String = "",
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
    }

    val agentId: String = savedStateHandle.get<String>("agentId")!!
    private var activeConversationId: String? = savedStateHandle.get<String>("conversationId")
    private val initialMessage: String? = savedStateHandle.get<String>("initialMessage")
    val scrollToMessageId: String? = savedStateHandle.get<String>("scrollToMessageId")
    val conversationId: String? get() = activeConversationId

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val chatBackground: StateFlow<ChatBackground> = settingsRepository.getChatBackgroundKey()
        .map { ChatBackground.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackground.Default)

    fun setChatBackground(background: ChatBackground) {
        viewModelScope.launch {
            settingsRepository.setChatBackgroundKey(background.key)
        }
    }

    private val pendingToolsMap = java.util.concurrent.ConcurrentHashMap<String, PendingToolCall>()
    private var hasSummary = false

    init {
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
            resolveConversationAndLoad()
        }
    }

    private fun resolveConversationAndLoad() {
        viewModelScope.launch {
            if (activeConversationId == null) {
                try {
                    conversationRepository.refreshConversationsIfStale(agentId, CONVERSATION_CACHE_TTL_MS)
                    val conversations = conversationRepository.getCachedConversations(agentId)
                    val mostRecent = conversations
                        .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
                        .firstOrNull()
                    if (mostRecent != null) {
                        activeConversationId = mostRecent.id
                        android.util.Log.d("ChatViewModel", "Resolved to most recent conversation: ${mostRecent.id}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatViewModel", "Failed to resolve conversation", e)
                }
            }
            loadMessagesInternal()

            initialMessage?.let { message ->
                if (message.isNotBlank()) {
                    sendMessage(message)
                }
            }
        }
    }

    private suspend fun loadMessagesInternal() {
        val requestedConversationId = activeConversationId
        val cachedAgent = agentRepository.getCachedAgent(agentId)
        val cachedMessages = messageRepository.getCachedMessages(agentId, requestedConversationId)
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
            val (agent, appMessages) = supervisorScope {
                val agentDeferred = async { agentRepository.getAgent(agentId).first() }
                val messagesDeferred = async { messageRepository.fetchMessages(agentId, requestedConversationId) }
                agentDeferred.await() to messagesDeferred.await()
            }
            if (requestedConversationId != activeConversationId) {
                return
            }
            _uiState.value = _uiState.value.copy(agentName = agent.name)
            val messages = appMessages.toUiMessages()
            if (messages.isNotEmpty()) hasSummary = true
            _uiState.value = _uiState.value.copy(
                messages = messages.toImmutableList(), isLoadingMessages = false
            )
        } catch (e: Exception) {
            if (requestedConversationId != activeConversationId) {
                return
            }
            _uiState.value = _uiState.value.copy(
                isLoadingMessages = false,
                error = e.message ?: "Failed to load messages",
            )
        }
    }

    fun loadMessages() {
        viewModelScope.launch { loadMessagesInternal() }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val userMessage = UiMessage(
                id = "pending-${System.currentTimeMillis()}",
                role = "user",
                content = text,
                timestamp = java.time.Instant.now().toString(),
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
                        val newConversation = conversationRepository.createConversation(agentId, summary)
                        convId = newConversation.id
                        activeConversationId = convId
                        hasSummary = true
                        android.util.Log.d("ChatViewModel", "Created new conversation: $convId")
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
                        android.util.Log.w("ChatViewModel", "Failed to set conversation summary", e)
                    }
                }
                messageRepository.sendMessage(agentId, text, convId).collect { state ->
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
                            reloadMessagesFromServer()
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

    private fun reloadMessagesFromServer() {
        viewModelScope.launch {
            try {
                val requestedConversationId = activeConversationId
                val appMessages = messageRepository.fetchMessages(agentId, requestedConversationId)
                if (requestedConversationId != activeConversationId) {
                    return@launch
                }
                val messages = appMessages.toUiMessages()
                if (messages.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(messages = messages.toImmutableList())
                }
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Silent reload failed", e)
            }
        }
    }

    fun resetMessages() {
        viewModelScope.launch {
            try {
                messageRepository.resetMessages(agentId)
                _uiState.value = _uiState.value.copy(messages = persistentListOf())
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Failed to reset messages", e)
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

}
