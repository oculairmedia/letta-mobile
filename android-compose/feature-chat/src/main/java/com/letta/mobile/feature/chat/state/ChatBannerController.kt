package com.letta.mobile.feature.chat.state

import com.letta.mobile.feature.chat.coordination.ChatComposerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.util.mapErrorToUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

    /**
     * letta-mobile-lgns8.19: Stop was pressed — mark the turn as cancelling but
     * KEEP [ChatUiState.isStreaming] true. The UI resolves to idle only when the
     * authoritative terminal frame (or the transport's synthetic-terminal
     * fallback) flips `isStreaming` off, which also clears
     * [ChatUiState.isCancellingRun]. Optimistically clearing here is what caused
     * ghost resume + message interleaving.
     */
    fun beginCancelling() {
        uiState.update {
            it.copy(
                isCancelling = true,
                error = null,
            )
        }
    }

    /**
     * Escape hatch for a SECOND Stop press while already cancelling (or a cancel
     * dispatch that failed outright): drop the local streaming UI without a
     * terminal frame. Telemetered by the caller — a forced clear means the
     * server turn may still be running.
     */
    fun forceClearStreamingAfterInterrupt() {
        uiState.update {
            it.copy(
                isStreaming = false,
                isAgentTyping = false,
                isCancelling = false,
                error = null,
            )
        }
    }

    /**
     * Drops any residual cancel marker at the start of a new turn, so a fresh
     * send can never inherit "stopping…" from the previous one.
     */
    fun clearCancelling() {
        if (!uiState.value.isCancelling) return
        uiState.update { it.copy(isCancelling = false) }
    }

    fun showComposerError(message: String) {
        composerController.setError(message)
    }

    fun clearComposerError() {
        composerController.clearError()
    }
}
