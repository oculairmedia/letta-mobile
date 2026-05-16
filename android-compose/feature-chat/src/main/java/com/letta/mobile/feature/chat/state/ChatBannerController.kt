package com.letta.mobile.feature.chat.state

import com.letta.mobile.feature.chat.ChatComposerController
import com.letta.mobile.feature.chat.ChatUiState
import com.letta.mobile.feature.chat.ClientModeConversationSwap
import com.letta.mobile.util.mapErrorToUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Centralizes chat-wide banner and conversation-level error mutations. */
internal class ChatBannerController(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val composerController: ChatComposerController,
) {
    fun showError(message: String) {
        uiState.update { it.copy(error = message) }
    }

    fun showMappedError(error: Exception, fallbackMessage: String) {
        showError(mapErrorToUserMessage(error, fallbackMessage))
    }

    fun clearError() {
        if (uiState.value.error == null) return
        uiState.update { it.copy(error = null) }
    }

    fun showNoAgentSelected() = showError("No agent selected")

    fun showConversationStillLoading() = showError("Conversation is still loading")

    fun showRetryConversationLoadBeforeSend() = showError("Retry conversation loading before sending a message")

    fun showNoActiveConversationToReset() = showError("No active conversation to reset")

    fun showClientModeConversationSwap(swap: ClientModeConversationSwap) {
        uiState.update { it.copy(clientModeConversationSwap = swap) }
    }

    fun dismissClientModeConversationSwap() {
        if (uiState.value.clientModeConversationSwap == null) return
        uiState.update { it.copy(clientModeConversationSwap = null) }
    }

    fun applyClientModeDisabled() {
        uiState.update {
            it.copy(
                isClientModeEnabled = false,
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
        }
    }

    fun applyClientModeEnabled(defaultPath: String?) {
        uiState.update {
            it.copy(
                isClientModeEnabled = true,
                clientModeLocation = it.clientModeLocation.copy(
                    defaultPath = it.clientModeLocation.defaultPath ?: defaultPath,
                ),
            )
        }
    }

    fun clearStreamingAfterInterrupt() {
        uiState.update {
            it.copy(
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
        }
    }

    fun showComposerError(message: String) {
        composerController.setError(message)
    }

    fun clearComposerError() {
        composerController.clearError()
    }
}
