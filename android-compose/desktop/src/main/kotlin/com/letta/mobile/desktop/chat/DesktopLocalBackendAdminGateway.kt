package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
        val request = ConversationListQuery(limit, archiveStatus)
        val response = appServerClient.conversationList(
            AppServerCommand.ConversationList(
                requestId = requestId(AppServerOperation.CONVERSATION_LIST),
                query = request.toJson(),
            ),
        )
        return ResponsePayload(response.success, response.error, response.conversations)
            .decodeList(AppServerOperation.CONVERSATION_LIST, Conversation.serializer())
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val response = appServerClient.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(
                requestId = requestId(AppServerOperation.CONVERSATION_RETRIEVE),
                conversationId = conversationId,
            ),
        )
        return ResponsePayload(response.success, response.error, response.conversation)
            .decode(AppServerOperation.CONVERSATION_RETRIEVE, Conversation.serializer())
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = listConversationMessages(
        ConversationMessagesRequest(
            conversationId = conversationId,
            query = MessageListQuery(limit, after, order),
        ),
    )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        val selectedConversationId = requireNotNull(conversationId) {
            "Bundled App Server message reads require conversationId"
        }
        return listConversationMessages(
            ConversationMessagesRequest(
                selectedConversationId,
                MessageListQuery(limit = limit, after = null, order = order),
            ),
        )
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val request = ConversationCreateRequest(agentId, summary)
        val response = appServerClient.conversationCreate(
            AppServerCommand.ConversationCreate(
                requestId = requestId(AppServerOperation.CONVERSATION_CREATE),
                body = request.toJson(),
            ),
        )
        return ResponsePayload(response.success, response.error, response.conversation)
            .decode(AppServerOperation.CONVERSATION_CREATE, Conversation.serializer())
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        updateConversation(conversationId, ConversationChange.Model(model))

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        updateConversation(conversationId, ConversationChange.Archived(archived))

    override suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException(
            "Bundled App Server does not support conversation deletion; archive explicitly instead",
        )
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

    private suspend fun listConversationMessages(request: ConversationMessagesRequest): List<LettaMessage> {
        val response = appServerClient.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = requestId(AppServerOperation.MESSAGE_LIST),
                conversationId = request.conversationId,
                query = request.query.toJson(),
            ),
        )
        return ResponsePayload(response.success, response.error, response.messages)
            .decodeList(AppServerOperation.MESSAGE_LIST, LettaMessage.serializer())
    }

    private suspend fun updateConversation(
        conversationId: String,
        change: ConversationChange,
    ): Conversation {
        val response = appServerClient.conversationUpdate(
            AppServerCommand.ConversationUpdate(
                requestId = requestId(AppServerOperation.CONVERSATION_UPDATE),
                conversationId = conversationId,
                body = change.body,
            ),
        )
        return ResponsePayload(response.success, response.error, response.conversation)
            .decode(AppServerOperation.CONVERSATION_UPDATE, Conversation.serializer())
    }

    private fun requestId(operation: AppServerOperation): String =
        "desktop-local-${operation.requestName}-${UUID.randomUUID()}"

    private fun <T> ResponsePayload<out JsonElement>.decode(
        operation: AppServerOperation,
        serializer: KSerializer<T>,
    ): T = desktopChatJson.decodeFromJsonElement(serializer, requireValue(operation))

    private fun <T> ResponsePayload<out JsonElement>.decodeList(
        operation: AppServerOperation,
        serializer: KSerializer<T>,
    ): List<T> = decode(operation, ListSerializer(serializer))

    private fun <T> ResponsePayload<T>.requireValue(operation: AppServerOperation): T {
        check(success) { error ?: operation.failureMessage }
        return value ?: error(operation.missingPayloadMessage)
    }

    private data class ResponsePayload<T>(
        val success: Boolean,
        val error: String?,
        val value: T?,
    )

    private data class ConversationListQuery(
        val limit: Int,
        val archiveStatus: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("limit", limit.toString())
            archiveStatus?.let { put("archive_status", it) }
            put("order", "desc")
            put("order_by", "last_message_at")
        }
    }

    private data class ConversationMessagesRequest(
        val conversationId: String,
        val query: MessageListQuery,
    )

    private data class MessageListQuery(
        val limit: Int?,
        val after: String?,
        val order: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            limit?.let { put("limit", it.toString()) }
            after?.let { put("after", it) }
            order?.let { put("order", it) }
        }
    }

    private data class ConversationCreateRequest(
        val agentId: String,
        val summary: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("agent_id", agentId)
            summary?.let { put("summary", it) }
        }
    }

    private sealed interface ConversationChange {
        val body: JsonObject

        data class Model(val model: String) : ConversationChange {
            override val body = buildJsonObject { put("model", model) }
        }

        data class Archived(val archived: Boolean) : ConversationChange {
            override val body = buildJsonObject { put("archived", archived) }
        }
    }

    private enum class AppServerOperation(
        val requestName: String,
        responseDescription: String,
        payloadDescription: String,
    ) {
        CONVERSATION_LIST("conversation-list", "conversation listing", "conversations"),
        CONVERSATION_RETRIEVE("conversation-get", "conversation retrieval", "conversation"),
        MESSAGE_LIST("message-list", "message listing", "messages"),
        CONVERSATION_CREATE("conversation-create", "conversation creation", "conversation"),
        CONVERSATION_UPDATE("conversation-update", "conversation update", "conversation"),
        ;

        val failureMessage = "Bundled App Server $responseDescription failed"
        val missingPayloadMessage = "Bundled App Server $responseDescription returned no $payloadDescription"
    }
}
