package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val inputText: String = "",
    val agentName: String = "",
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
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    private val conversationId: String? = savedStateHandle.get<String>("conversationId")

    private val _uiState = MutableStateFlow<UiState<ChatUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ChatUiState>> = _uiState.asStateFlow()

    init {
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val agent = agentRepository.getAgent(agentId).first()
                val appMessages = messageRepository.getMessages(agentId, conversationId).first()
                val messages = appMessages.map { it.toUiMessage() }
                _uiState.value = UiState.Success(
                    ChatUiState(messages = messages, agentName = agent.name)
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load messages")
            }
        }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val currentState = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(currentState.copy(
                inputText = "",
                isStreaming = true,
                isAgentTyping = true
            ))
            try {
                messageRepository.sendMessage(agentId, text, conversationId).collect { state ->
                    when (state) {
                        is StreamState.Sending -> {
                            val current = (_uiState.value as? UiState.Success)?.data ?: return@collect
                            _uiState.value = UiState.Success(current.copy(isAgentTyping = true))
                        }
                        is StreamState.Streaming -> {
                            val messages = state.messages.map { it.toUiMessage() }
                            val current = (_uiState.value as? UiState.Success)?.data ?: return@collect
                            _uiState.value = UiState.Success(
                                current.copy(messages = messages, isStreaming = true, isAgentTyping = false)
                            )
                        }
                        is StreamState.ToolExecution -> {
                            val current = (_uiState.value as? UiState.Success)?.data ?: return@collect
                            _uiState.value = UiState.Success(current.copy(isAgentTyping = true))
                        }
                        is StreamState.Complete -> {
                            val messages = state.messages.map { it.toUiMessage() }
                            val current = (_uiState.value as? UiState.Success)?.data ?: return@collect
                            _uiState.value = UiState.Success(
                                current.copy(messages = messages, isStreaming = false, isAgentTyping = false)
                            )
                        }
                        is StreamState.Error -> {
                            _uiState.value = UiState.Error(state.message)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to send message")
            }
        }
    }

    fun updateInputText(text: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(inputText = text))
        }
    }

    private fun AppMessage.toUiMessage(): UiMessage {
        val role = when (messageType) {
            MessageType.USER -> "user"
            MessageType.ASSISTANT -> "assistant"
            MessageType.REASONING -> "assistant"
            MessageType.TOOL_CALL -> "tool"
            MessageType.TOOL_RETURN -> "tool"
        }
        val toolCalls = if (messageType == MessageType.TOOL_CALL && toolName != null) {
            listOf(UiToolCall(name = toolName, arguments = content, result = null))
        } else null

        return UiMessage(
            id = id,
            role = role,
            content = content,
            timestamp = date.toString(),
            isReasoning = messageType == MessageType.REASONING,
            toolCalls = toolCalls
        )
    }
}
