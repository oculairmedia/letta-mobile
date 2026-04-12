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
    val messages: List<UiMessage> = emptyList(),
    val isLoadingMessages: Boolean = true,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: List<PendingToolCall> = emptyList(),
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

    val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    private var activeConversationId: String? = savedStateHandle.get<String>("conversationId")
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
            loadMessages()
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            val cachedAgent = agentRepository.getCachedAgent(agentId)
            val cachedMessages = messageRepository.getCachedMessages(agentId, activeConversationId)
            if (cachedAgent != null || cachedMessages.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    agentName = cachedAgent?.name ?: _uiState.value.agentName,
                    messages = if (cachedMessages.isNotEmpty()) cachedMessages.toUiMessages() else _uiState.value.messages,
                    isLoadingMessages = cachedMessages.isEmpty(),
                    error = null,
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoadingMessages = true)
            }
            try {
                val (agent, appMessages) = supervisorScope {
                    val agentDeferred = async { agentRepository.getAgent(agentId).first() }
                    val messagesDeferred = async { messageRepository.fetchMessages(agentId, activeConversationId) }
                    agentDeferred.await() to messagesDeferred.await()
                }
                _uiState.value = _uiState.value.copy(agentName = agent.name)
                val messages = appMessages.toUiMessages()
                if (messages.isNotEmpty()) hasSummary = true
                _uiState.value = _uiState.value.copy(
                    messages = messages, isLoadingMessages = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingMessages = false,
                    error = e.message ?: "Failed to load messages",
                )
            }
        }
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
            val existingMessages = _uiState.value.messages + userMessage
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
                                messages = existingMessages + newMessages,
                                isStreaming = true,
                                isAgentTyping = false,
                            )
                        }
                        is StreamState.ToolExecution -> {
                            val toolCall = PendingToolCall(id = state.toolName, name = state.toolName)
                            pendingToolsMap[state.toolName] = toolCall
                            _uiState.value = _uiState.value.copy(
                                isAgentTyping = true,
                                pendingTools = pendingToolsMap.values.toList(),
                            )
                        }
                        is StreamState.Complete -> {
                            pendingToolsMap.clear()
                            val newMessages = state.messages.toUiMessages()
                            _uiState.value = _uiState.value.copy(
                                messages = existingMessages + newMessages,
                                isStreaming = false,
                                isAgentTyping = false,
                                pendingTools = emptyList(),
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
                val appMessages = messageRepository.fetchMessages(agentId, activeConversationId)
                val messages = appMessages.toUiMessages()
                if (messages.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(messages = messages)
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
                _uiState.value = _uiState.value.copy(messages = emptyList())
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Failed to reset messages", e)
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

}
