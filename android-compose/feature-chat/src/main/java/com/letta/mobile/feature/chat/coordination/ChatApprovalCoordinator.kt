package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.MessageApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
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
    ): ChatApprovalResult = submitApproval(agentId, activeConversationId, ChatApprovalAnswer(requestId, toolCallIds, approve, reason))

    /** letta-mobile-bzvro.40: [answer] may carry always-allow rule ids, which only an Iroh host applies. */
    suspend fun submitApproval(agentId: String, activeConversationId: String?, answer: ChatApprovalAnswer): ChatApprovalResult {
        if (activeConversationId.isNullOrBlank()) return ChatApprovalResult.MissingActiveConversation
        if (agentId.isBlank()) return ChatApprovalResult.MissingActiveAgent

        return try {
            messageRepository.submitApproval(answer.toMessageAnswer(AgentId(agentId), activeConversationId))
            ChatApprovalResult.Submitted
        } catch (cancelled: CancellationException) {
            // Not a failed submission: ChatApprovalController must see the cancellation to release
            // the in-flight card, instead of showing an error banner.
            throw cancelled
        } catch (e: Exception) {
            ChatApprovalResult.Failed(e.message ?: "Failed to submit approval")
        }
    }
}

private fun ChatApprovalAnswer.toMessageAnswer(agentId: AgentId, conversationId: String) = MessageApprovalAnswer(
    agentId = agentId,
    approvalRequestId = requestId,
    toolCallIds = toolCallIds,
    approve = approve,
    reason = reason,
    conversationId = conversationId,
    selectedSuggestionIds = selectedSuggestionIds,
)

internal sealed interface ChatApprovalResult {
    data object MissingActiveAgent : ChatApprovalResult
    data object MissingActiveConversation : ChatApprovalResult
    data object Submitted : ChatApprovalResult
    data class Failed(val message: String) : ChatApprovalResult
}
