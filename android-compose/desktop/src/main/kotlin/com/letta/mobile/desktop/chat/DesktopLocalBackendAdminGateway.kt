package com.letta.mobile.desktop.chat

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
) : DesktopAdminChatGateway {
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

    override suspend fun deleteConversation(conversationId: String): Unit = throw UnsupportedOperationException(
        "Bundled App Server does not support conversation deletion; archive explicitly instead",
    )

    override suspend fun createAgent(params: AgentCreateParams): Agent =
        throw UnsupportedOperationException("Local agent creation is not supported yet")

    override suspend fun listLlmModels(): List<LlmModel> = emptyList()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = throw UnsupportedOperationException("Turns are owned by the bundled App Server")

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        flowOf(TimelineStreamFrame.Heartbeat)

    override fun close() = Unit
}
