package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppServerLocalAdminGateway(
    private val client: AppServerClient,
    private val requestId: (String) -> String,
) {
    suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
        val response = client.conversationList(
            AppServerCommand.ConversationList(
                requestId = requestId(Operation.ConversationList.requestName),
                query = buildJsonObject {
                    put("limit", limit.toString())
                    archiveStatus?.let { put("archive_status", it) }
                    put("order", "desc")
                    put("order_by", "last_message_at")
                },
            ),
        )
        return Payload(response.success, response.error, response.conversations)
            .decodeList(Operation.ConversationList, Conversation.serializer())
    }

    suspend fun getConversation(conversationId: String): Conversation {
        val response = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(
                requestId = requestId(Operation.ConversationRetrieve.requestName),
                conversationId = conversationId,
            ),
        )
        return Payload(response.success, response.error, response.conversation)
            .decode(Operation.ConversationRetrieve, Conversation.serializer())
    }

    suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val response = client.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = requestId(Operation.MessageList.requestName),
                conversationId = conversationId,
                query = buildJsonObject {
                    limit?.let { put("limit", it.toString()) }
                    after?.let { put("after", it) }
                    order?.let { put("order", it) }
                },
            ),
        )
        return Payload(response.success, response.error, response.messages)
            .decodeList(Operation.MessageList, LettaMessage.serializer())
    }

    suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val response = client.conversationCreate(
            AppServerCommand.ConversationCreate(
                requestId = requestId(Operation.ConversationCreate.requestName),
                body = buildJsonObject {
                    put("agent_id", agentId)
                    summary?.let { put("summary", it) }
                },
            ),
        )
        return Payload(response.success, response.error, response.conversation)
            .decode(Operation.ConversationCreate, Conversation.serializer())
    }

    suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        updateConversation(conversationId, buildJsonObject { put("model", model) })

    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        updateConversation(conversationId, buildJsonObject { put("archived", archived) })

    private suspend fun updateConversation(conversationId: String, body: JsonObject): Conversation {
        val response = client.conversationUpdate(
            AppServerCommand.ConversationUpdate(
                requestId = requestId(Operation.ConversationUpdate.requestName),
                conversationId = conversationId,
                body = body,
            ),
        )
        return Payload(response.success, response.error, response.conversation)
            .decode(Operation.ConversationUpdate, Conversation.serializer())
    }

    private fun <T> Payload<out JsonElement>.decode(operation: Operation, serializer: KSerializer<T>): T =
        AppServerProtocol.json.decodeFromJsonElement(serializer, requireValue(operation))

    private fun <T> Payload<out JsonElement>.decodeList(operation: Operation, serializer: KSerializer<T>): List<T> =
        decode(operation, ListSerializer(serializer))

    private fun <T> Payload<T>.requireValue(operation: Operation): T {
        check(success) { error ?: operation.failureMessage }
        return value ?: error(operation.missingPayloadMessage)
    }

    private data class Payload<T>(val success: Boolean, val error: String?, val value: T?)

    private enum class Operation(
        val requestName: String,
        responseDescription: String,
        payloadDescription: String,
    ) {
        ConversationList("conversation-list", "conversation listing", "conversations"),
        ConversationRetrieve("conversation-get", "conversation retrieval", "conversation"),
        MessageList("message-list", "message listing", "messages"),
        ConversationCreate("conversation-create", "conversation creation", "conversation"),
        ConversationUpdate("conversation-update", "conversation update", "conversation"),
        ;

        val failureMessage = "Bundled App Server $responseDescription failed"
        val missingPayloadMessage = "Bundled App Server $responseDescription returned no $payloadDescription"
    }
}
