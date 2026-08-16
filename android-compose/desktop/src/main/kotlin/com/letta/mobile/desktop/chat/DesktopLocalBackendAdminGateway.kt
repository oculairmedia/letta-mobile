package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Authoritative admin surface for the bundled local backend. Reads and writes
 * use the same child-owned App Server session as turns, never its backing files.
 */
internal class DesktopLocalBackendAdminGateway(
    private val appServerClient: AppServerClient,
) : DesktopAdminChatGateway {
    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
        val response = appServerClient.conversationList(
            AppServerCommand.ConversationList(
                requestId = requestId("conversation-list"),
                query = buildJsonObject {
                    put("limit", limit.toString())
                    archiveStatus?.let { put("archive_status", it) }
                    put("order", "desc")
                    put("order_by", "last_message_at")
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server conversation listing failed" }
        val rows = response.conversations
            ?: error("Bundled App Server conversation listing returned no conversations")
        return rows.decodeList(Conversation.serializer())
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val response = appServerClient.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(
                requestId = requestId("conversation-get"),
                conversationId = conversationId,
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server conversation retrieval failed" }
        return response.conversation?.let {
            desktopChatJson.decodeFromJsonElement(Conversation.serializer(), it)
        } ?: error("Bundled App Server conversation retrieval returned no conversation")
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val response = appServerClient.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = requestId("message-list"),
                conversationId = conversationId,
                query = buildJsonObject {
                    limit?.let { put("limit", it.toString()) }
                    after?.let { put("after", it) }
                    order?.let { put("order", it) }
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server message listing failed" }
        val rows = response.messages ?: error("Bundled App Server message listing returned no messages")
        return rows.decodeList(LettaMessage.serializer())
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        if (conversationId != null) {
            return listConversationMessages(conversationId, limit, after = null, order = order)
        }
        val messages = mutableListOf<LettaMessage>()
        for (conversation in listConversations(limit = 10_000, archiveStatus = null)) {
            if (conversation.agentId.value == agentId) {
                messages += listConversationMessages(
                    conversation.id.value,
                    limit = null,
                    after = null,
                    order = order,
                )
            }
        }
        return limit?.let(messages::takeLast) ?: messages
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val response = appServerClient.conversationCreate(
            AppServerCommand.ConversationCreate(
                requestId = requestId("conversation-create"),
                body = buildJsonObject {
                    put("agent_id", agentId)
                    summary?.let { put("summary", it) }
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server conversation creation failed" }
        val conversation = response.conversation?.let {
            desktopChatJson.decodeFromJsonElement(Conversation.serializer(), it)
        } ?: error("Bundled App Server conversation creation returned no conversation")
        return conversation
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        updateConversation(conversationId, buildJsonObject { put("model", model) })

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        updateConversation(conversationId, buildJsonObject { put("archived", archived) })

    override suspend fun deleteConversation(conversationId: String) {
        setConversationArchived(conversationId, archived = true)
    }

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

    private suspend fun updateConversation(
        conversationId: String,
        body: kotlinx.serialization.json.JsonObject,
    ): Conversation {
        val response = appServerClient.conversationUpdate(
            AppServerCommand.ConversationUpdate(
                requestId = requestId("conversation-update"),
                conversationId = conversationId,
                body = body,
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server conversation update failed" }
        return response.conversation?.let {
            desktopChatJson.decodeFromJsonElement(Conversation.serializer(), it)
        } ?: error("Bundled App Server conversation update returned no conversation")
    }

    private fun requestId(operation: String): String = "desktop-local-$operation-${UUID.randomUUID()}"

    private fun <T> JsonArray.decodeList(serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        desktopChatJson.decodeFromJsonElement(ListSerializer(serializer), this)
}
