package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.paging.MessagePagingSource
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless HTTP helper for non-streaming message endpoints.
 *
 * `MessageRepository` is deliberately **not** the chat timeline source of
 * truth. Streaming sends, live sync, optimistic/local writes, reconciliation,
 * and visible conversation state are owned by
 * [com.letta.mobile.data.timeline.TimelineRepository]. Keep chat screens wired
 * to the timeline first; use this repository only for bounded HTTP operations
 * that augment or administer that state:
 *
 * - older-message pagination that is merged back through the timeline observer
 * - message search
 * - approvals and cancellations
 * - batch-message administration
 * - agent-level reset
 * - conversation inspector/debug reads
 *
 * Do not reintroduce an in-memory message cache, streaming send entry point,
 * or conversation-state Flow here. Phase 5 of the Timeline migration removed
 * the old `_pendingMessages`, `_streamingMessages`, `_serverMessages`, and
 * legacy `sendMessage()` responsibilities so there is only one live timeline
 * owner.
 */
@Singleton
open class MessageRepository @Inject constructor(
    private val messageApi: MessageApi,
    private val irohApprovalSource: IrohAdminRpcApprovalSource? = null,
    private val irohTimelineTransport: com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport? = null,
) : IMessageRepository, IConversationInspectorMessageRepository {
    companion object {
        const val INITIAL_FETCH_LIMIT = 30
        const val OLDER_MESSAGES_PAGE_SIZE = 20
        const val DEFAULT_FETCH_LIMIT = INITIAL_FETCH_LIMIT
        const val TARGETED_FETCH_LIMIT = 100
        const val MAX_TARGETED_FETCH_PAGES = 20
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun getMessagesPaged(agentId: AgentId?, conversationId: ConversationId?): Flow<PagingData<AppMessage>> {
        return Pager(
            config = PagingConfig(
                pageSize = MessagePagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = MessagePagingSource.PAGE_SIZE
            ),
            pagingSourceFactory = { MessagePagingSource(messageApi, agentId, conversationId) }
        ).flow
    }

    override suspend fun fetchMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        targetMessageId: String?,
    ): List<AppMessage> = MessageRepositoryFetch.fetchMessages(
        messageApi = messageApi,
        agentId = agentId,
        conversationId = conversationId,
        targetMessageId = targetMessageId,
        defaultFetchLimit = DEFAULT_FETCH_LIMIT,
        targetedFetchLimit = TARGETED_FETCH_LIMIT,
        maxTargetedFetchPages = MAX_TARGETED_FETCH_PAGES,
    )

    override suspend fun fetchOlderMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
    ): List<AppMessage> = MessageRepositoryFetch.fetchOlderMessages(
        messageApi = messageApi,
        irohTimelineTransport = irohTimelineTransport,
        agentId = agentId,
        conversationId = conversationId,
        beforeMessageId = beforeMessageId,
        olderMessagesPageSize = OLDER_MESSAGES_PAGE_SIZE,
    )

    override suspend fun cancelMessage(agentId: AgentId, runIds: List<String>?): Map<String, String> {
        return messageApi.cancelMessage(agentId = agentId, runIds = runIds)
    }

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> {
        return messageApi.searchMessages(request)
    }

    override suspend fun createBatch(request: CreateBatchMessagesRequest): Job {
        return messageApi.createBatch(request)
    }

    override suspend fun retrieveBatch(batchId: String): Job {
        return messageApi.retrieveBatch(batchId)
    }

    override suspend fun listBatches(): List<Job> {
        return messageApi.listBatches(limit = 1000)
    }

    override suspend fun listBatchMessages(batchId: String, agentId: AgentId?): BatchMessagesResponse {
        return messageApi.listBatchMessages(batchId = batchId, limit = 1000, agentId = agentId?.value)
    }

    override suspend fun cancelBatch(batchId: String) {
        messageApi.cancelBatch(batchId)
    }

    override suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage> {
        return messageApi.listConversationMessages(conversationId, limit = 200, order = "asc")
            .map { it.toInspectorMessage() }
    }

    override suspend fun fetchLatestConversationInspectorMessages(
        conversationId: ConversationId,
        limit: Int,
    ): List<ConversationInspectorMessage> {
        return messageApi.listConversationMessages(conversationId, limit = limit, order = "desc")
            .map { it.toInspectorMessage() }
    }

    override suspend fun submitApproval(
        agentId: AgentId,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        MessageRepositoryApproval.submitApproval(
            messageApi = messageApi,
            irohApprovalSource = irohApprovalSource,
            json = json,
            agentId = agentId,
            approvalRequestId = approvalRequestId,
            toolCallIds = toolCallIds,
            approve = approve,
            reason = reason,
        )
    }

    override suspend fun resetMessages(agentId: AgentId) {
        messageApi.resetMessages(agentId)
    }
}
