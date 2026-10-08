package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.transport.appserver.AppServerClient
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class DesktopLocalBackendAdminGateway(
    appServerClient: AppServerClient,
) : DesktopAdminChatGateway, ConversationSummaryGateway, ConversationForkGateway {
    private val shared = AppServerLocalAdminGateway(appServerClient) { operation ->
        "desktop-local-$operation-${UUID.randomUUID()}"
    }

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
        shared.listConversations(limit, archiveStatus)

    override suspend fun getConversation(conversationId: String): Conversation =
        shared.getConversation(conversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = shared.listConversationMessages(conversationId, limit, after, order)

    override suspend fun listConversationMessagesBefore(
        conversationId: String,
        limit: Int,
        before: String,
        order: String,
    ): List<LettaMessage> = shared.listConversationMessagesBefore(conversationId, limit, before, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = shared.listConversationMessages(
        requireNotNull(conversationId) { "Bundled App Server message reads require conversationId" },
        limit,
        after = null,
        order = order,
    )

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        shared.createConversation(agentId, summary)

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        shared.setConversationModel(conversationId, model)

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        shared.setConversationArchived(conversationId, archived)

    /**
     * letta-mobile-bzvro.17: the bundled App Server has no `conversation_delete`, so a delete
     * archives and hides the conversation; it leaves every list and stays recoverable on disk.
     */
    override suspend fun deleteConversation(conversationId: String) {
        shared.setConversationRemoved(com.letta.mobile.data.model.ConversationId(conversationId), removed = true)
    }

    /** letta-mobile-bzvro.17: rename (and the generated title) is the conversation's `summary`. */
    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation =
        shared.renameConversation(update)

    /** letta-mobile-bzvro.15: `conversation_fork` through the bundled App Server. */
    override suspend fun forkConversation(request: ConversationForkRequest): Conversation =
        shared.forkConversation(request)

    override suspend fun createAgent(params: AgentCreateParams): Agent =
        shared.createAgent(params)

    override suspend fun listLlmModels(): List<LlmModel> = shared.listLlmModels()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = throw UnsupportedOperationException("Turns are owned by the bundled App Server")

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        flowOf(TimelineStreamFrame.Heartbeat)

    override fun close() = Unit
}
