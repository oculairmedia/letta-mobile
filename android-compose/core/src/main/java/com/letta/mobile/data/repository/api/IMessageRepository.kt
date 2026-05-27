package com.letta.mobile.data.repository.api

import androidx.paging.PagingData
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
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
    fun getMessagesPaged(agentId: String?, conversationId: String?): Flow<PagingData<AppMessage>>

    /**
     * Stateless recent/targeted fetch. Use only for explicit backfill or
     * inspector-style reads; live chat observation belongs to TimelineRepository.
     */
    suspend fun fetchMessages(
        agentId: String,
        conversationId: String,
        targetMessageId: String? = null,
    ): List<AppMessage>

    /**
     * Older-page backfill for chat history. Callers must merge returned data
     * into the timeline projection rather than treating this as a replacement
     * message source.
     */
    suspend fun fetchOlderMessages(
        agentId: String,
        conversationId: String,
        beforeMessageId: String,
    ): List<AppMessage>

    suspend fun cancelMessage(agentId: String, runIds: List<String>? = null): Map<String, String>
    suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult>
    suspend fun createBatch(request: CreateBatchMessagesRequest): Job
    suspend fun retrieveBatch(batchId: String): Job
    suspend fun listBatches(): List<Job>
    suspend fun listBatchMessages(batchId: String, agentId: String? = null): BatchMessagesResponse
    suspend fun cancelBatch(batchId: String)

    suspend fun submitApproval(
        agentId: String,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    )

    /**
     * Reset all messages for an agent (agent-scoped, not conversation-scoped).
     *
     * Keep this contract aligned with MessageApi.resetMessages(agentId); do not
     * add a conversation-id overload unless there is a real conversation-scoped
     * API path behind it.
     */
    suspend fun resetMessages(agentId: String)
}
