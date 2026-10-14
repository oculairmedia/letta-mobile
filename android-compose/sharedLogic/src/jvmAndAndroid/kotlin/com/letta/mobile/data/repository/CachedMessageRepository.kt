package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.MessageIrohTimelineSource
import com.letta.mobile.data.repository.api.MessageRemoteSource
import com.letta.mobile.data.repository.api.OlderMessagesPage
import kotlinx.serialization.json.Json

/**
 * Phase 5p platform-neutral stateless message helper.
 *
 * This is **not** the chat timeline source of truth; [TimelineRepository] owns live chat
 * state, streaming sends, optimistic writes, and reconciliation. Do not reintroduce those
 * responsibilities here.
 */
open class CachedMessageRepository(
    private val remote: RemoteRoute<MessageRemoteSource>,
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
        remote.source().cancelMessage(agentId, runIds)

    override suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> =
        remote.source().searchMessages(request)

    override suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage> =
        remote.source().listConversationMessages(conversationId, limit = 200, after = null, order = "asc")
            .map { it.toInspectorMessage() }

    override suspend fun fetchLatestConversationInspectorMessages(
        conversationId: ConversationId,
        limit: Int,
    ): List<ConversationInspectorMessage> =
        remote.source().listConversationMessages(conversationId, limit = limit, after = null, order = "desc")
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

    override suspend fun submitApproval(answer: com.letta.mobile.data.repository.api.MessageApprovalAnswer) {
        MessageRepositoryApproval.submitApproval(
            ApprovalSubmitParams(
                remote = remote,
                irohApprovalSource = irohApprovalSource,
                json = json,
                agentId = answer.agentId,
                approvalRequestId = answer.approvalRequestId,
                toolCallIds = answer.toolCallIds,
                approve = answer.approve,
                reason = answer.reason,
                conversationId = answer.conversationId,
                selectedSuggestionIds = answer.selectedSuggestionIds,
            ),
        )
    }

    override suspend fun resetMessages(agentId: AgentId) {
        remote.source().resetMessages(agentId)
    }
}
