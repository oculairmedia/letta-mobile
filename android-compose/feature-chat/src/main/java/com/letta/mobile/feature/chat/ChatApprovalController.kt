package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ChatApprovalController(
    private val scope: CoroutineScope,
    private val coordinator: ChatApprovalCoordinator,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val bannerController: ChatBannerController,
    private val agentId: String,
    private val activeConversationId: () -> String?,
) {
    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        scope.launch {
            markApprovalInFlight(requestId)

            try {
                when (val result = coordinator.submitApproval(
                    agentId = agentId,
                    activeConversationId = activeConversationId(),
                    requestId = requestId,
                    toolCallIds = toolCallIds,
                    approve = approve,
                    reason = reason,
                )) {
                    ChatApprovalResult.Submitted -> Unit
                    ChatApprovalResult.MissingActiveAgent ->
                        bannerController.showError("No active agent available for approval")
                    ChatApprovalResult.MissingActiveConversation ->
                        bannerController.showError("No active conversation available for approval")
                    is ChatApprovalResult.Failed ->
                        bannerController.showError(result.message)
                }
            } catch (e: Exception) {
                bannerController.showError(e.message ?: "Failed to submit approval")
            } finally {
                clearApprovalInFlight(requestId)
            }
        }
    }

    private fun markApprovalInFlight(requestId: String) {
        uiState.update {
            it.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )
        }
    }

    private fun clearApprovalInFlight(requestId: String) {
        uiState.update {
            if (it.activeApprovalRequestId != requestId) {
                it
            } else {
                it.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = null,
                )
            }
        }
    }
}
