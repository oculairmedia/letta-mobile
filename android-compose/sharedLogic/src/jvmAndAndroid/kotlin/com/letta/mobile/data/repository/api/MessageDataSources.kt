package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult

/**
 * Remote HTTP message admin surface used by [com.letta.mobile.data.repository.CachedMessageRepository].
 * Platform modules supply Ktor/[MessageApi] bindings; Iroh older-page reads go through
 * [MessageIrohTimelineSource]; approvals may route through [com.letta.mobile.data.repository.IrohAdminRpcApprovalSource].
 */
interface MessageRemoteSource {
    suspend fun fetchRecentMessages(
        conversationId: ConversationId,
        messageLimit: Int,
        beforeMessageId: String?,
    ): List<LettaMessage>

    suspend fun listMessages(
        agentId: AgentId,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage>

    suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage>

    suspend fun sendMessage(agentId: AgentId, request: MessageCreateRequest): LettaResponse

    suspend fun cancelMessage(agentId: AgentId, runIds: List<String>?): Map<String, String>

    suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult>

    suspend fun createBatch(request: CreateBatchMessagesRequest): Job

    suspend fun retrieveBatch(batchId: String): Job

    suspend fun listBatches(
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): List<Job>

    suspend fun listBatchMessages(
        batchId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        agentId: String?,
    ): BatchMessagesResponse

    suspend fun cancelBatch(batchId: String)

    suspend fun resetMessages(agentId: AgentId)
}

/**
 * Iroh admin_rpc older-history reads for [com.letta.mobile.data.repository.CachedMessageRepository].
 * Implemented by [com.letta.mobile.data.repository.IrohAdminRpcMessageTimelineSource] on Android.
 */
interface MessageIrohTimelineSource {
    fun shouldUseIroh(): Boolean

    suspend fun listOlderConversationMessages(
        conversationId: String,
        beforeMessageId: String,
        limit: Int,
    ): List<LettaMessage>

    suspend fun listOlderConversationMessagesPage(
        conversationId: String,
        beforeMessageId: String,
        limit: Int,
    ): MessageTimelinePage
}

/** Platform-neutral counterpart to the timeline transport's trimmed page wrapper. */
data class MessageTimelinePage(
    val messages: List<LettaMessage>,
    val hasMore: Boolean?,
)
