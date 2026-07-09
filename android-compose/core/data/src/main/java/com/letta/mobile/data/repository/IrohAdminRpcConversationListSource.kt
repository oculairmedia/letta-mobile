package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcConversationListSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    suspend fun listConversations(
        agentId: AgentId?,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation> {
        val params = buildJsonObject {
            agentId?.value?.let { put("agent_id", it) }
            limit?.let { put("limit", it.toString()) }
            after?.let { put("after", it) }
            archiveStatus?.let { put("archive_status", it) }
            summarySearch?.let { put("summary_search", it) }
            order?.let { put("order", it) }
            orderBy?.let { put("order_by", it) }
        }
        val response = channelTransport.adminRpc(
            method = "conversation.list",
            path = "/v1/conversations",
            body = params.toString(),
        )
        if (!response.success) {
            error(response.error ?: "Iroh admin_rpc conversation.list failed")
        }
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Conversation.serializer()), result)
    }

    // letta-mobile-qfa81 (P4 rows 3-6): conversation B-tier reads/writes whose
    // server handlers already exist (ConversationAdminHandlers). Client wiring
    // only. summary-update + fork are C-tier (no handler) and stay on the HTTP
    // path where the LettaApiClient choke point hard-fails them in iroh:// mode.

    suspend fun getConversation(id: ConversationId): Conversation {
        val response = channelTransport.adminRpc(
            method = "conversation.get",
            path = "/v1/conversations/${id.value}",
            body = null,
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc conversation.get failed")
        val result = response.result ?: error("Iroh admin_rpc conversation.get returned no result")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }

    suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            summary?.let { put("summary", it) }
        }
        val response = channelTransport.adminRpc(
            method = "conversation.create",
            path = "/v1/agents/${agentId.value}/conversations",
            body = body.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc conversation.create failed")
        val result = response.result ?: error("Iroh admin_rpc conversation.create returned no result")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }

    suspend fun deleteConversation(id: ConversationId) {
        val response = channelTransport.adminRpc(
            method = "conversation.delete",
            path = "/v1/conversations/${id.value}",
            body = null,
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc conversation.delete failed")
    }

    /**
     * Archive (true) or restore/unarchive (false) a conversation. The archive
     * state is a field on the conversation, so both map to the same resource
     * (`/v1/conversations/{id}`); the method name selects the value the server
     * handler PATCHes. The handler returns the updated [Conversation].
     */
    suspend fun setConversationArchived(id: ConversationId, archived: Boolean): Conversation {
        val method = if (archived) "conversation.archive" else "conversation.restore"
        val response = channelTransport.adminRpc(
            method = method,
            path = "/v1/conversations/${id.value}",
            body = null,
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc $method failed")
        val result = response.result ?: error("Iroh admin_rpc $method returned no result")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }
}
