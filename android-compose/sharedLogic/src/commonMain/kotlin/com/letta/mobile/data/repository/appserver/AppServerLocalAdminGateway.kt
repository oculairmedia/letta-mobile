package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.controller.node.iroh.withDefaultContextWindow
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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

    /**
     * `agent_create` over the bundled App Server's native Listen V2 protocol.
     * Verified against `@letta-ai/letta-code` 0.29.12 (`letta.js`): the local
     * runtime's WS dispatcher handles `agent_create` as a first-class command
     * (`backend.createAgent(body)`), passing the body straight through to
     * `LocalBackend`/`HeadlessBackend` — the same shape the REST `/v1/agents`
     * create body uses. [withDefaultContextWindow] is the same body default
     * already proven for [AppServerCommand.AgentCreate] over the Iroh admin
     * bridge (see `AgentAdminHandlers.register("agent.create")`); reused here
     * rather than re-deriving the default.
     */
    suspend fun createAgent(params: AgentCreateParams): Agent {
        val body = AppServerProtocol.json.encodeToJsonElement(AgentCreateParams.serializer(), params)
            .jsonObject
            .withDefaultContextWindow()
        val response = client.agentCreate(
            AppServerCommand.AgentCreate(
                requestId = requestId(Operation.AgentCreate.requestName),
                body = body,
            ),
        )
        return Payload(response.success, response.error, response.agent)
            .decode(Operation.AgentCreate, Agent.serializer())
    }

    /**
     * `list_models` over the bundled App Server's native Listen V2 protocol.
     * Verified against `@letta-ai/letta-code` 0.29.12 (`letta.js`,
     * `handleModelToolsetCommand`): unlike `admin_rpc` (which the local
     * runtime never handles — its `app_server_info` capability advertisement
     * has no `admin_rpc` flag and the string never appears in the bundle),
     * `list_models` IS wired end to end, backed by `LocalBackend`'s real
     * model/provider catalog (`capabilities.localModelCatalog = true`), so it
     * reflects providers (including litellm) the user has actually
     * configured. Entries share the exact presentation shape
     * [AppServerListModelsAdapter] already decodes for the Iroh admin_rpc
     * `model.list` response — reused here rather than duplicated. Mirrors
     * [com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway.listLlmModels]:
     * an unsuccessful/empty response degrades to an empty catalog instead of
     * throwing, so a transient local-runtime hiccup doesn't crash the model
     * picker.
     */
    suspend fun listLlmModels(): List<LlmModel> {
        val response = client.listModels(
            AppServerCommand.ListModels(requestId = requestId(Operation.ListModels.requestName)),
        )
        val entries = response.entries.takeIf { response.success } ?: return emptyList()
        return AppServerListModelsAdapter.toLlmModels(entries)
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
        AgentCreate("agent-create", "agent creation", "agent"),
        ListModels("list-models", "model listing", "models"),
        ;

        val failureMessage = "Bundled App Server $responseDescription failed"
        val missingPayloadMessage = "Bundled App Server $responseDescription returned no $payloadDescription"
    }
}
