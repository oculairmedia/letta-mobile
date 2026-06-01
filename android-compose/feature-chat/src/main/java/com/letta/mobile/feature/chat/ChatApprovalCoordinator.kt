package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.MessageRepository

internal class ChatApprovalCoordinator(
    private val messageRepository: MessageRepository,
) {
    suspend fun submitApproval(
        agentId: String,
        activeConversationId: String?,
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ): ChatApprovalResult {
        if (activeConversationId.isNullOrBlank()) return ChatApprovalResult.MissingActiveConversation
        if (agentId.isBlank()) return ChatApprovalResult.MissingActiveAgent

        return try {
            messageRepository.submitApproval(
                agentId = AgentId(agentId),
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
    data object MissingActiveAgent : ChatApprovalResult
    data object MissingActiveConversation : ChatApprovalResult
    data object Submitted : ChatApprovalResult
    data class Failed(val message: String) : ChatApprovalResult
}
