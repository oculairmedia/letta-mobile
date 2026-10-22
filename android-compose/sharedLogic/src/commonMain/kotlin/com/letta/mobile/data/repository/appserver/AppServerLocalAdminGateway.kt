package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatConversationStore
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.controller.node.iroh.withDefaultContextWindow
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.decodeConversationList
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationFork
import com.letta.mobile.data.transport.appserver.AppServerConversationForkBody
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompile
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompileBody
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class AppServerLocalAdminGateway(
    private val client: AppServerClient,
    private val requestId: (String) -> String,
) {
    /** One page of an agent's own conversations, newest first (`conversation_list`'s `agent_id` filter and `after` cursor). */
    suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> {
        val response = client.conversationList(
            AppServerCommand.ConversationList(
                requestId = requestId(Operation.ConversationList.requestName),
                query = buildJsonObject {
                    put("agent_id", page.agent.value)
                    // A number: letta-code's local store ignores a string limit and returns 20.
                    put("limit", page.limit)
                    page.after?.let { put("after", it.value) }
                    put("archive_status", page.archiveStatus)
                    put("order", "desc")
                    put("order_by", "last_message_at")
                },
            ),
        )
        return AppServerProtocol.json.decodeConversationList(
            Payload(response.success, response.error, response.conversations).requireValue(Operation.ConversationList),
        )
    }

    suspend fun getConversation(conversation: ConversationId): Conversation {
        val response = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(
                requestId = requestId(Operation.ConversationRetrieve.requestName),
                conversationId = conversation.value,
            ),
        )
        return Payload(response.success, response.error, response.conversation)
            .decode(Operation.ConversationRetrieve, Conversation.serializer())
    }

    suspend fun listConversationMessages(
        conversation: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = listMessages(conversation, MessagePageQuery(limit, after, null, order))

    /** The older-history read. Separate from the tail read because a page is bounded by one cursor. */
    suspend fun listConversationMessagesBefore(
        conversation: ConversationId,
        limit: Int,
        before: String,
        order: String,
    ): List<LettaMessage> = listMessages(conversation, MessagePageQuery(limit, null, before, order))

    /** The cursors one `message.list` accepts, so neither read grows a parameter for the other's. */
    private data class MessagePageQuery(
        val limit: Int?,
        val after: String?,
        val before: String?,
        val order: String?,
    )

    private suspend fun listMessages(
        conversation: ConversationId,
        query: MessagePageQuery,
    ): List<LettaMessage> {
        val response = client.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = requestId(Operation.MessageList.requestName),
                conversationId = conversation.value,
                query = buildJsonObject {
                    query.limit?.let { put("limit", it) }
                    query.after?.let { put("after", it) }
                    query.before?.let { put("before", it) }
                    query.order?.let { put("order", it) }
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

    suspend fun createConversation(agent: AgentId, summary: String?): Conversation {
        val response = client.conversationCreate(
            AppServerCommand.ConversationCreate(
                requestId = requestId(Operation.ConversationCreate.requestName),
                body = buildJsonObject {
                    put("agent_id", agent.value)
                    summary?.let { put("summary", it) }
                },
            ),
        )
        return Payload(response.success, response.error, response.conversation)
            .decode(Operation.ConversationCreate, Conversation.serializer())
    }

    suspend fun setConversationModel(conversation: ConversationId, model: String): Conversation =
        updateConversation(conversation, buildJsonObject { put("model", model) })

    suspend fun setConversationArchived(conversation: ConversationId, archived: Boolean): Conversation =
        updateConversation(conversation, buildJsonObject { put("archived", archived) })

    /** The conversation's title is its `summary`. */
    suspend fun renameConversation(update: ConversationSummaryUpdate): Conversation =
        updateConversation(update.conversationId, buildJsonObject { put("summary", update.summary.value) })

    /**
     * The App Server has no `conversation_delete`, so a delete archives the
     * conversation AND hides it: hidden conversations never reach a list (`include_hidden` is off),
     * and `removed = false` brings it back for an undo.
     */
    suspend fun setConversationRemoved(conversationId: ConversationId, removed: Boolean): Conversation =
        updateConversation(
            conversationId,
            buildJsonObject {
                put("archived", removed)
                put("hidden", removed)
            },
        )

    /**
     * The undo of [setConversationRemoved]: un-hides the conversation and puts `archived` back to
     * what it was before the delete ([archived] true for a chat that was already archived).
     */
    suspend fun restoreRemovedConversation(conversationId: ConversationId, archived: Boolean): Conversation =
        updateConversation(
            conversationId,
            buildJsonObject {
                put("archived", archived)
                put("hidden", false)
            },
        )

    /**
     * `conversation_fork`, then a read of the fork (upstream answers with
     * its id only). The options go in the body: without it the whole conversation is copied.
     */
    suspend fun forkConversation(request: ConversationForkRequest): Conversation {
        val response = client.conversationFork(
            AppServerConversationFork(
                requestId = requestId(Operation.ConversationFork.requestName),
                conversationId = request.conversationId,
                body = AppServerConversationForkBody(
                    agentId = request.agentId,
                    messageId = request.throughMessageId,
                ),
            ),
        )
        val forkedId = Payload(response.success, response.error, response.conversationId)
            .requireValue(Operation.ConversationFork)
        return getConversation(ConversationId(forkedId))
    }

    /** `conversation_recompile`; throws with the server's reason when it refuses. */
    suspend fun recompileConversation(request: ConversationRecompileRequest) {
        val response = client.conversationRecompile(
            AppServerConversationRecompile(
                requestId = requestId(Operation.ConversationRecompile.requestName),
                conversationId = request.conversationId,
                body = AppServerConversationRecompileBody(agentId = request.agentId.takeIf(String::isNotBlank)),
            ),
        )
        check(response.success) { response.error ?: Operation.ConversationRecompile.failureMessage }
    }

    private suspend fun updateConversation(conversation: ConversationId, body: JsonObject): Conversation {
        val response = client.conversationUpdate(
            AppServerCommand.ConversationUpdate(
                requestId = requestId(Operation.ConversationUpdate.requestName),
                conversationId = conversation.value,
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
        ConversationFork("conversation-fork", "conversation fork", "conversation"),
        ConversationRecompile("conversation-recompile", "system prompt recompile", "result"),
        AgentCreate("agent-create", "agent creation", "agent"),
        ListModels("list-models", "model listing", "models"),
        ;

        val failureMessage = "Bundled App Server $responseDescription failed"
        val missingPayloadMessage = "Bundled App Server $responseDescription returned no $payloadDescription"
    }
}

/**
 * An App Server session's conversation operations over its own protocol commands
 * (`conversation_list` / `_create` / `_update` / `_fork` / `_recompile`), as the chat gateway
 * capabilities every host probes for. Desktop's App Server gateway and the web's ws:// gateway
 * both answer through it. An Iroh host takes none of these commands (only
 * `admin_rpc`), so it is served by [com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway]
 * instead.
 */
class AppServerConversationAdmin(private val admin: AppServerLocalAdminGateway) :
    ChatConversationStore,
    ChatGatewayExtras,
    ConversationSummaryGateway,
    ConversationForkGateway,
    ConversationRecompileGateway {
    /** The agent's own conversations; filtered here too, for a store that ignores `agent_id`. */
    override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> =
        admin.listAgentConversations(page).filter { it.agentId == page.agent }

    override suspend fun getConversation(conversationId: String): Conversation = admin.getConversation(ConversationId(conversationId))

    /**
     * The App Server has no `conversation_delete`, so a delete archives and hides the conversation;
     * it leaves every list (Archived too) and stays on disk, and the undo brings it back.
     */
    override suspend fun deleteConversation(conversationId: String) {
        admin.setConversationRemoved(ConversationId(conversationId), removed = true)
    }

    override val deleteBehavior: ConversationDeleteBehavior get() = ConversationDeleteBehavior.RemovesFromLists

    /** The undo of [deleteConversation]: un-hide, and archived again only if it was archived before. */
    override suspend fun restoreDeletedConversation(conversationId: String, wasArchived: Boolean) {
        admin.restoreRemovedConversation(ConversationId(conversationId), archived = wasArchived)
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        admin.createConversation(AgentId(agentId), summary)

    override suspend fun createAgent(params: AgentCreateParams): Agent = admin.createAgent(params)

    override suspend fun listLlmModels(): List<LlmModel> = admin.listLlmModels()

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        admin.setConversationModel(ConversationId(conversationId), model)

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        admin.setConversationArchived(ConversationId(conversationId), archived)

    /** Rename (and the generated title) is the conversation's `summary`. */
    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation = admin.renameConversation(update)

    override suspend fun forkConversation(request: ConversationForkRequest): Conversation = admin.forkConversation(request)

    override suspend fun recompileConversation(request: ConversationRecompileRequest) = admin.recompileConversation(request)
}
