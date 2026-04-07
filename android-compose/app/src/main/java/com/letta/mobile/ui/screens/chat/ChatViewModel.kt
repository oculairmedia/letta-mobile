package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Message(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val isReasoning: Boolean = false,
    val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val name: String,
    val arguments: String,
    val result: String?
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isStreaming: Boolean = false,
    val inputText: String = "",
    val agentName: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    
    private val _uiState = MutableStateFlow<UiState<ChatUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ChatUiState>> = _uiState.asStateFlow()

    init {
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                TODO("Wire to repository")
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
                isStreaming = true
            ))
            try {
                TODO("Wire to repository")
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
}
