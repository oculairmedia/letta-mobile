package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.approval.ChatApprovalResult
import com.letta.mobile.data.chat.approval.OutboxApproval
import com.letta.mobile.data.chat.approval.OutboxSubmitResult
import com.letta.mobile.data.chat.approval.OutboxTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.MessageApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import kotlinx.coroutines.CancellationException

internal class ChatApprovalCoordinator(
    private val messageRepository: MessageRepository,
    /**
     * letta-mobile-bzvro.14: routes an answer through the offline outbox (sent now, or queued while the
     * link is down); returns null when the answer goes straight to the repository.
     */
    private val outbox: suspend (OutboxApproval) -> OutboxSubmitResult? = { null },
) {
    /** letta-mobile-bzvro.40: [answer] may carry always-allow rule ids, which only an Iroh host applies. */
    suspend fun submitApproval(agentId: String, activeConversationId: String?, answer: ChatApprovalAnswer): ChatApprovalResult {
        if (activeConversationId.isNullOrBlank()) return ChatApprovalResult.MissingActiveConversation
        if (agentId.isBlank()) return ChatApprovalResult.MissingActiveAgent

        return try {
            val queued = outbox(OutboxApproval.of(OutboxTarget(agentId, activeConversationId), answer))
            queued?.toChatResult() ?: run {
                messageRepository.submitApproval(answer.toMessageAnswer(AgentId(agentId), activeConversationId))
                ChatApprovalResult.Submitted
            }
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

/** letta-mobile-bzvro.14: what the outbox did with an answer, as the chat reports it. */
private fun OutboxSubmitResult.toChatResult(): ChatApprovalResult = when (this) {
    OutboxSubmitResult.Sent, OutboxSubmitResult.Duplicate -> ChatApprovalResult.Submitted
    OutboxSubmitResult.Queued -> ChatApprovalResult.Queued
    OutboxSubmitResult.NoLongerPending -> ChatApprovalResult.Failed("This approval is no longer pending.")
}
