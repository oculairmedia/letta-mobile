package com.letta.mobile.data.repository.api

import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import kotlinx.coroutines.flow.Flow

/**
 * Stateless HTTP message operations boundary.
 *
 * This interface intentionally exposes one-shot server calls only. It must not
 * become the source of truth for live chat timeline state; streaming sends,
 * optimistic messages, reconciliation, and conversation-state observation live
 * behind `TimelineRepository` instead. Chat features that need historical data
 * may page/search through this interface, but must merge those results into the
 * timeline-backed UI model rather than replacing it.
 */
interface IMessageRepository : IConversationInspectorMessageRepository {
    /**
     * Legacy paging endpoint for admin/list surfaces, not the live chat stream.
     */
    fun getMessagesPaged(agentId: AgentId?, conversationId: ConversationId?): Flow<PagingData<AppMessage>>
    fun getMessagesPaged(agentId: String?, conversationId: String?): Flow<PagingData<AppMessage>> =
        getMessagesPaged(agentId?.let(::AgentId), conversationId?.let(::ConversationId))

    /**
     * Stateless recent/targeted fetch. Use only for explicit backfill or
     * inspector-style reads; live chat observation belongs to TimelineRepository.
     */
    suspend fun fetchMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        targetMessageId: String? = null,
    ): List<AppMessage>
    suspend fun fetchMessages(agentId: String, conversationId: String, targetMessageId: String? = null): List<AppMessage> =
        fetchMessages(AgentId(agentId), ConversationId(conversationId), targetMessageId)

    /**
     * Older-page backfill for chat history. Callers must merge returned data
     * into the timeline projection rather than treating this as a replacement
     * message source.
     */
    suspend fun fetchOlderMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
    ): List<AppMessage>
    suspend fun fetchOlderMessages(agentId: String, conversationId: String, beforeMessageId: String): List<AppMessage> =
        fetchOlderMessages(AgentId(agentId), ConversationId(conversationId), beforeMessageId)

    /**
     * letta-mobile-f0ixs: older-page backfill that also reports whether MORE history exists.
     *
     * Callers previously inferred "reached the beginning of the conversation" from a page
     * shorter than the requested size. That inference is wrong whenever MessageListPageGuard
     * trims an oversized window to fit its byte budget: the page comes back short even though
     * older history remains, so scroll-back stopped early and silently. Long conversations with
     * large messages are both where the guard trims and where scroll-back matters.
     *
     * [OlderMessagesPage.hasMore] is null when the transport gives no explicit signal (the HTTP
     * path, and untrimmed Iroh pages), so callers fall back to the page-size heuristic and
     * behaviour there is unchanged.
     *
     * Defaulted rather than abstract on purpose: every existing implementor and test fake keeps
     * compiling, and only the repository that can actually answer overrides it.
     */
    suspend fun fetchOlderMessagesPage(
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
    ): OlderMessagesPage = OlderMessagesPage(
        messages = fetchOlderMessages(agentId, conversationId, beforeMessageId),
        hasMore = null,
    )

    suspend fun cancelMessage(agentId: AgentId, runIds: List<String>? = null): Map<String, String>
    suspend fun cancelMessage(agentId: String, runIds: List<String>? = null): Map<String, String> = cancelMessage(AgentId(agentId), runIds)
    suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult>

    suspend fun submitApproval(
        agentId: AgentId,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
        // letta-mobile-vilsn: the already-validated active conversation id, threaded
        // to the Iroh admin_rpc source so the host controller answers the RIGHT
        // conversation when several runtimes are open for the same agent.
        conversationId: String? = null,
    )
    suspend fun submitApproval(
        agentId: String,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
        conversationId: String? = null,
    ) = submitApproval(AgentId(agentId), approvalRequestId, toolCallIds, approve, reason, conversationId)

    /**
     * letta-mobile-bzvro.40: [submitApproval] that may carry always-allow rule ids. A repository that
     * cannot deliver them refuses rather than approve without the rule.
     */
    suspend fun submitApproval(answer: MessageApprovalAnswer) {
        require(answer.selectedSuggestionIds.isEmpty()) { "always-allow rules need an Iroh host" }
        submitApproval(answer.agentId, answer.approvalRequestId, answer.toolCallIds, answer.approve, answer.reason, answer.conversationId)
    }

    /**
     * Reset all messages for an agent (agent-scoped, not conversation-scoped).
     *
     * Keep this contract aligned with MessageApi.resetMessages(agentId); do not
     * add a conversation-id overload unless there is a real conversation-scoped
     * API path behind it.
     */
    suspend fun resetMessages(agentId: AgentId)
    suspend fun resetMessages(agentId: String) = resetMessages(AgentId(agentId))
}

/** letta-mobile-bzvro.40: one approval answer, with the always-allow rule ids the person chose. */
data class MessageApprovalAnswer(
    val agentId: AgentId,
    val approvalRequestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String? = null,
    val conversationId: String? = null,
    val selectedSuggestionIds: List<String> = emptyList(),
)

/** letta-mobile-bzvro.14: an answer the offline outbox replays, as the repository sends it. */
fun com.letta.mobile.data.chat.approval.OutboxApproval.toMessageAnswer() = MessageApprovalAnswer(
    agentId = AgentId(agentId),
    approvalRequestId = requestId,
    toolCallIds = toolCallIds,
    approve = approve,
    reason = reason,
    conversationId = conversationId,
    selectedSuggestionIds = selectedSuggestionIds,
)
