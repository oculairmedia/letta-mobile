package com.letta.mobile.data.repository

import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.branch.IrohConversationForkRpc
import com.letta.mobile.data.chat.recompile.IrohConversationRecompileClient
import com.letta.mobile.data.chat.recompile.asAdminRpcLink
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HiddenListFilter
import com.letta.mobile.data.model.decodeConversationList
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.reportSkippedConversationRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcConversationListSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = LettaJson.adminRpc,
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendUsesChannel()

    /** One agent's conversations through the host's `conversation.list` (server default page and archive filter). */
    suspend fun listConversations(agentId: AgentId): List<Conversation> =
        conversationList(buildJsonObject { put("agent_id", agentId.value) }).visibleConversationsInLists()

    /**
     * One page of an agent's conversations, newest first, as the host sent it: the shared list filters
     * it and pages on from its last row, so a filtered row never ends paging early.
     */
    suspend fun listAgentConversations(agent: AgentId, after: ConversationId?, limit: Int): List<Conversation> =
        conversationList(conversationListParams(agent, after, limit))

    /**
     * The host's visible recents ([visibleRecentsParams]). A failed answer is the host's own error
     * (`capability_unavailable` without a visibility index) and a host that ignored the opt-in is
     * unsupported, so the shared listing can keep its per-agent reads for such a host.
     */
    suspend fun listRecentConversations(limit: Int, after: ConversationId?): List<Conversation> {
        val response = channelTransport.adminRpc(
            method = "conversation.list",
            path = "/v1/conversations",
            body = visibleRecentsParams(limit, after).toString(),
        )
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "Iroh admin_rpc visible recents failed")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "Visible recents returned no result")
        return json.decodeVisibleRecents(result)
    }

    private suspend fun conversationList(params: JsonObject): List<Conversation> {
        val response = channelTransport.adminRpc(
            method = "conversation.list",
            path = "/v1/conversations",
            body = params.toString(),
        )
        if (!response.success) {
            error(response.error ?: "Iroh admin_rpc conversation.list failed")
        }
        val result = response.result ?: return emptyList()
        return json.decodeConversationList(result, ::reportSkippedConversationRows)
    }

    // letta-mobile-i9h61.3.2: agent-scoped conversation list — the
    // tap-to-navigate picker reads this for the OTHER agent's
    // conversations (the same data the recipient's router uses to
    // route at receive time). Distinct from [listConversations] (the
    // App Server v2 command), which is not agent-scoped in a way the
    // client can rely on for cross-agent routing. Wire shape mirrors
    // the App Server v2 list (per the conversation.list_agent handler
    // in sharedLogic).
    suspend fun listConversationsForAgent(
        agentId: AgentId,
        limit: Int? = null,
    ): List<Conversation> {
        val params = buildJsonObject {
            put("agent_id", agentId.value)
            limit?.let { put("limit", it.toString()) }
        }
        val response = channelTransport.adminRpc(
            method = "conversation.list_agent",
            path = "/v1/conversations/list_agent",
            body = params.toString(),
        )
        if (!response.success) {
            // capability_unavailable (no local backend) or unknown agent
            // — both fall through cleanly to emptyList() so the picker
            // falls back to appserver-resolved fresh conversation.
            return emptyList()
        }
        val result = response.result ?: return emptyList()
        return json.decodeConversationList(result, ::reportSkippedConversationRows).visibleConversationsInLists()
    }

    // letta-mobile-qfa81 (P4 rows 3-6): conversation reads/writes whose
    // server handlers already exist (ConversationAdminHandlers).

    suspend fun getConversation(id: ConversationId): Conversation =
        conversationRpc(ConversationRpcCall("conversation.get", "/v1/conversations/${id.value}"))

    suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            summary?.let { put("summary", it) }
        }
        return conversationRpc(ConversationRpcCall("conversation.create", "/v1/conversations", body.toString()))
    }

    suspend fun updateConversation(id: ConversationId, summary: String): Conversation {
        val body = buildJsonObject { put("summary", summary) }
        return conversationRpc(ConversationRpcCall("conversation.update", "/v1/conversations/${id.value}", body.toString()))
    }

    /** The host's `conversation.fork`, which answers with the full fork. */
    suspend fun forkConversation(request: ConversationForkRequest): Conversation =
        conversationRpc(
            ConversationRpcCall(
                method = IrohConversationForkRpc.METHOD,
                path = IrohConversationForkRpc.path(request.conversationId),
                body = IrohConversationForkRpc.params(request).toString(),
            ),
        )

    /**
     * The host's `conversation.recompile` relay; [IrohConversationRecompileClient.supported]
     * says whether the connected host advertises it.
     */
    val recompiler = IrohConversationRecompileClient(channelTransport.asAdminRpcLink())

    suspend fun deleteConversation(id: ConversationId) {
        // App Server v2 has no conversation_delete; archive is the supported lifecycle.
        setConversationArchived(id, archived = true)
    }

    /**
     * Archive (true) or restore/unarchive (false) a conversation. The archive
     * state is a field on the conversation, so both map to the same resource
     * (`/v1/conversations/{id}`); the method name selects the value the server
     * handler PATCHes. The handler returns the updated [Conversation].
     */
    suspend fun setConversationArchived(id: ConversationId, archived: Boolean): Conversation {
        val method = if (archived) "conversation.archive" else "conversation.restore"
        return conversationRpc(ConversationRpcCall(method, "/v1/conversations/${id.value}"))
    }

    /** One conversation read or write; each answers with the conversation. */
    private data class ConversationRpcCall(val method: String, val path: String, val body: String? = null)

    private suspend fun conversationRpc(call: ConversationRpcCall): Conversation {
        val response = channelTransport.adminRpc(method = call.method, path = call.path, body = call.body)
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${call.method} failed")
        val result = response.result ?: error("Iroh admin_rpc ${call.method} returned no result")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }
}

/** The host's `conversation.list` params for one agent's page: the agent, the window and the newest-first order. */
internal fun conversationListParams(
    agent: AgentId,
    after: ConversationId?,
    limit: Int,
    archiveStatus: String = ARCHIVE_STATUS_ALL,
): JsonObject = buildJsonObject {
    put("agent_id", agent.value)
    put("limit", limit.toString())
    after?.let { put("after", it.value) }
    put("archive_status", archiveStatus)
    put("order", "desc")
    put("order_by", "last_message_at")
}

/** The host's `conversation.list` params for visible recents (`conversation.list/visible-recents-v1`). */
internal fun visibleRecentsParams(limit: Int, after: ConversationId?): JsonObject = buildJsonObject {
    put("visible_only", true)
    put("limit", limit)
    after?.let { put("after", it.value) }
}

/**
 * A visible recents answer, decoded. A host that ignored `visible_only` answers agent-free, archived
 * or hidden rows; that host is unsupported, checked on the raw rows before decoding can drop them.
 */
internal fun Json.decodeVisibleRecents(result: JsonElement): List<Conversation> {
    if (result is JsonArray && result.any(::isLegacyRecentRow)) {
        throw UnsupportedOperationException("Host ignored visible_only")
    }
    return decodeConversationList(result, ::reportSkippedConversationRows)
}

private fun isLegacyRecentRow(row: JsonElement): Boolean {
    if (row !is JsonObject) return false
    if (HiddenListFilter.isAgentFreeRow(row)) return true
    return recentFlagIsTrue(row, "archived") || recentFlagIsTrue(row, "hidden")
}

private fun recentFlagIsTrue(row: JsonObject, key: String): Boolean = (row[key] as? JsonPrimitive)?.booleanOrNull == true

/** Every conversation, active and archived: the lists filter archived rows themselves. */
private const val ARCHIVE_STATUS_ALL = "all"
