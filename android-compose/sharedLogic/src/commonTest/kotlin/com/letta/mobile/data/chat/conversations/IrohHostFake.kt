package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One conversation as an Iroh host stores and answers it. */
internal data class HostConversation(
    val id: String,
    /** Null for an agent-free worker conversation (the Letta API's ephemeral ones). */
    val agent: String?,
    val summary: String? = null,
    val archived: Boolean = false,
    val lastMessageAt: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("agent_id", agent)
        summary?.let { put("summary", it) }
        put("archived", archived)
        lastMessageAt?.let { put("last_message_at", it) }
    }
}

/**
 * An App Server client to an Iroh host: `admin_rpc` answers from an in-memory conversation store,
 * and the App Server's own commands are refused (recorded in [native]), as an Iroh host refuses
 * them. [chatGateway] is the shared [IrohAdminRpcChatGateway] over the session channel the web
 * builds, so a test runs the real path below the owner.
 */
internal class IrohHost(vararg listed: HostConversation) : AppServerClient {
    private val conversations = listed.toMutableList()
    private var made = 0
    private var forked = 0

    /** Every `admin_rpc` method called, in order. */
    val methods = mutableListOf<String>()

    /** Every `admin_rpc` call's params, in order. */
    val calls = mutableListOf<Pair<String, JsonObject>>()

    /** The App Server's own commands sent here (an Iroh host refuses them all). */
    val native = mutableListOf<String>()

    /** Methods that fail, as a host does when it cannot do them. */
    val failing = mutableSetOf<String>()

    fun chatGateway(scope: CoroutineScope): ChatGateway = IrohAdminRpcChatGateway(AppServerClientChannel(this, scope))

    fun stored(id: String): HostConversation? = conversations.firstOrNull { it.id == id }

    override val events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 32)

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        val params = command.params ?: JsonObject(emptyMap())
        methods += command.method
        calls += command.method to params
        if (command.method in failing) return failed(command, "${command.method} denied")
        val result = answer(command.method, params) ?: return failed(command, "unknown ${command.method}")
        return AppServerInboundFrame.AdminRpcResponse(command.requestId, success = true, result = result)
    }

    private fun answer(method: String, params: JsonObject): JsonElement? = when (method) {
        "conversation.list" -> JsonArray(listed(params).map(HostConversation::json))
        "conversation.create" -> HostConversation("new-${++made}", params.text("agent_id")).also { conversations.add(0, it) }.json()
        "conversation.update" -> edit(params) { it.copy(summary = params.text("summary")) }
        "conversation.archive" -> edit(params) { it.copy(archived = true) }
        "conversation.restore" -> edit(params) { it.copy(archived = false) }
        "conversation.fork" -> HostConversation("fork-${++forked}", params.text("agent_id")).also { conversations.add(0, it) }.json()
        "conversation.get" -> stored(params.text("conversation_id"))?.json()
        "message.list" -> JsonArray(emptyList())
        else -> null
    }

    /** The App Server's list: the asked agent's (every row without one), after the cursor, a page at most. */
    private fun listed(params: JsonObject): List<HostConversation> {
        val agent = params.text("agent_id").takeIf(String::isNotEmpty)
        val rows = conversations.filter { agent == null || it.agent == agent }
        val after = params.text("after").takeIf(String::isNotEmpty)
        val from = after?.let { id -> rows.indexOfFirst { it.id == id } + 1 } ?: 0
        val limit = params.text("limit").toIntOrNull() ?: rows.size
        return rows.drop(from).take(limit)
    }

    private fun edit(params: JsonObject, change: (HostConversation) -> HostConversation): JsonElement? {
        val id = params.text("conversation_id")
        val index = conversations.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        conversations[index] = change(conversations[index])
        return conversations[index].json()
    }

    override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse =
        refuse("conversation_list")

    override suspend fun conversationCreate(
        command: AppServerCommand.ConversationCreate,
    ): AppServerInboundFrame.ConversationCreateResponse = refuse("conversation_create")

    override suspend fun conversationUpdate(
        command: AppServerCommand.ConversationUpdate,
    ): AppServerInboundFrame.ConversationUpdateResponse = refuse("conversation_update")

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        refuse("runtime_start")

    override suspend fun input(command: AppServerCommand.Input) = refuse<Unit>("input")

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = refuse("sync")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = refuse("abort")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = refuse<Unit>("tool")

    private fun <T> refuse(command: String): T {
        native += command
        throw UnsupportedOperationException("the Iroh host refuses $command")
    }

    private fun failed(command: AppServerCommand.AdminRpc, reason: String) =
        AppServerInboundFrame.AdminRpcResponse(command.requestId, success = false, error = reason)
}

/** A param the host reads as text (the gateway sends some numbers as strings, others as numbers). */
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

