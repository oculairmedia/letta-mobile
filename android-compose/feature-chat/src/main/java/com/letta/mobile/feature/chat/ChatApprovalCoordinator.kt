package com.letta.mobile.feature.chat

import com.letta.mobile.data.repository.MessageRepository

internal class ChatApprovalCoordinator(
    private val messageRepository: MessageRepository,
) {
    suspend fun submitApproval(
        activeConversationId: String?,
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ): ChatApprovalResult {
        val conversationId = activeConversationId
            ?: return ChatApprovalResult.MissingActiveConversation

        return try {
            messageRepository.submitApproval(
                conversationId = conversationId,
                approvalRequestId = requestId,
                toolCallIds = toolCallIds,
                approve = approve,
                reason = reason,
            )
            ChatApprovalResult.Submitted
        } catch (e: Exception) {
            ChatApprovalResult.Failed(e.message ?: "Failed to submit approval")
        }
    }
}

internal sealed interface ChatApprovalResult {
    data object MissingActiveConversation : ChatApprovalResult
    data object Submitted : ChatApprovalResult
    data class Failed(val message: String) : ChatApprovalResult
}
