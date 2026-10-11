package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.MessageApprovalAnswer
import kotlinx.coroutines.CancellationException

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
        selectedSuggestionIds: List<String> = emptyList(),
    ): ChatApprovalResult {
        if (activeConversationId.isNullOrBlank()) return ChatApprovalResult.MissingActiveConversation
        if (agentId.isBlank()) return ChatApprovalResult.MissingActiveAgent

        return try {
            if (selectedSuggestionIds.isEmpty()) {
                messageRepository.submitApproval(
                    agentId = AgentId(agentId),
                    approvalRequestId = requestId,
                    toolCallIds = toolCallIds,
                    approve = approve,
                    reason = reason,
                    conversationId = activeConversationId,
                )
            } else {
                messageRepository.submitApproval(alwaysAllow(agentId, activeConversationId, requestId, toolCallIds, selectedSuggestionIds))
            }
            ChatApprovalResult.Submitted
        } catch (cancelled: CancellationException) {
            // Not a failed submission: ChatApprovalController must see the cancellation to release
            // the in-flight card, instead of showing an error banner.
            throw cancelled
        } catch (e: Exception) {
            ChatApprovalResult.Failed(e.message ?: "Failed to submit approval")
        }
    }

    /** letta-mobile-bzvro.40: an approval that also persists the server-offered rules (an Iroh host only). */
    private fun alwaysAllow(
        agentId: String,
        conversationId: String,
        requestId: String,
        toolCallIds: List<String>,
        suggestionIds: List<String>,
    ) = MessageApprovalAnswer(
        agentId = AgentId(agentId),
        approvalRequestId = requestId,
        toolCallIds = toolCallIds,
        approve = true,
        conversationId = conversationId,
        selectedSuggestionIds = suggestionIds,
    )
}

internal sealed interface ChatApprovalResult {
    data object MissingActiveAgent : ChatApprovalResult
    data object MissingActiveConversation : ChatApprovalResult
    data object Submitted : ChatApprovalResult
    data class Failed(val message: String) : ChatApprovalResult
}
