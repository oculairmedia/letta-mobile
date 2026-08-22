package com.letta.mobile.data.repository

import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.BatchMessagesResponse
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.CreateBatchMessagesRequest
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.MessageIrohTimelineSource
import com.letta.mobile.data.repository.api.MessageRemoteSource
import com.letta.mobile.data.repository.api.OlderMessagesPage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Phase 5p platform-neutral stateless message helper. Paging stays in the Android binder.
 *
 * This is **not** the chat timeline source of truth; [TimelineRepository] owns live chat
 * state, streaming sends, optimistic writes, and reconciliation. Do not reintroduce those
 * responsibilities here.
 */
open class CachedMessageRepository(
    private val remote: MessageRemoteSource,
    private val irohTimelineSource: MessageIrohTimelineSource? = null,
    private val irohApprovalSource: IrohAdminRpcApprovalSource? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IMessageRepository, IConversationInspectorMessageRepository {
    companion object {
        const val INITIAL_FETCH_LIMIT = 30
        const val OLDER_MESSAGES_PAGE_SIZE = 20
        const val DEFAULT_FETCH_LIMIT = INITIAL_FETCH_LIMIT
        const val TARGETED_FETCH_LIMIT = 100
        const val MAX_TARGETED_FETCH_PAGES = 20
    }

    override fun getMessagesPaged(agentId: AgentId?, conversationId: ConversationId?): Flow<PagingData<AppMessage>> {
        error("getMessagesPaged is Android-bound; override in MessageRepository")
    }

    override suspend fun fetchMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        targetMessageId: String?,
    ): List<AppMessage> = MessageRepositoryFetch.fetchMessages(
        MessageFetchParams(
            remote = remote,
            agentId = agentId,
            conversationId = conversationId,
            targetMessageId = targetMessageId,
            defaultFetchLimit = DEFAULT_FETCH_LIMIT,
            targetedFetchLimit = TARGETED_FETCH_LIMIT,
            maxTargetedFetchPages = MAX_TARGETED_FETCH_PAGES,
        ),
    )

    override suspend fun fetchOlderMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
    ): List<AppMessage> = MessageRepositoryFetch.fetchOlderMessages(
        remote = remote,
        irohTimelineSource = irohTimelineSource,
        agentId = agentId,
        conversationId = conversationId,
        beforeMessageId = beforeMessageId,
        olderMessagesPageSize = OLDER_MESSAGES_PAGE_SIZE,
    )

    override suspend fun fetchOlderMessagesPage(
        agentId: AgentId,
        conversationId: ConversationId,
        beforeMessageId: String,
    ): OlderMessagesPage = MessageRepositoryFetch.fetchOlderMessagesPage(
        remote = remote,
        irohTimelineSource = irohTimelineSource,
        agentId = agentId,
        conversationId = conversationId,
        beforeMessageId = beforeMessageId,
        olderMessagesPageSize = OLDER_MESSAGES_PAGE_SIZE,
    )

    override suspend fun cancelMessage(agentId: AgentId, runIds: List<String>?): Map<String, String> =
        remote.cancelMessage(agentId, runIds)

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> =
        remote.searchMessages(request)

    override suspend fun createBatch(request: CreateBatchMessagesRequest): Job =
        remote.createBatch(request)

    override suspend fun retrieveBatch(batchId: String): Job =
        remote.retrieveBatch(batchId)

    override suspend fun listBatches(): List<Job> =
        exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.BOUNDED_MAX_PAGES,
            fetch = { limit, after ->
                remote.listBatches(
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                )
            },
            extractCursor = { job -> job.id },
            dedupKey = { job -> job.id },
        )

    override suspend fun listBatchMessages(batchId: String, agentId: AgentId?): BatchMessagesResponse {
        val messages = exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.BOUNDED_MAX_PAGES,
            fetch = { limit, after ->
                remote.listBatchMessages(
                    batchId = batchId,
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                    agentId = agentId?.value,
                ).messages
            },
            extractCursor = { message -> message.id },
            dedupKey = { message -> message.id },
        )
        return BatchMessagesResponse(messages = messages)
    }

    override suspend fun cancelBatch(batchId: String) {
        remote.cancelBatch(batchId)
    }

    override suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage> =
        remote.listConversationMessages(conversationId, limit = 200, after = null, order = "asc")
            .map { it.toInspectorMessage() }

    override suspend fun fetchLatestConversationInspectorMessages(
        conversationId: ConversationId,
        limit: Int,
    ): List<ConversationInspectorMessage> =
        remote.listConversationMessages(conversationId, limit = limit, after = null, order = "desc")
            .map { it.toInspectorMessage() }

    override suspend fun submitApproval(
        agentId: AgentId,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
        conversationId: String?,
    ) {
        MessageRepositoryApproval.submitApproval(
            ApprovalSubmitParams(
                remote = remote,
                irohApprovalSource = irohApprovalSource,
                json = json,
                agentId = agentId,
                approvalRequestId = approvalRequestId,
                toolCallIds = toolCallIds,
                approve = approve,
                reason = reason,
                conversationId = conversationId,
            ),
        )
    }

    override suspend fun resetMessages(agentId: AgentId) {
        remote.resetMessages(agentId)
    }
}
