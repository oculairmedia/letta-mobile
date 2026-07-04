package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class IrohAdminRpcTimelineTransport(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        httpGated("/v1/conversations/$conversationId/messages")
        throw TimelineTransportHttpException(0, "HTTP send is gated while backend is iroh://")
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        httpGated("/v1/conversations/$conversationId/stream")
        throw TimelineNoActiveRunException(conversationId)
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val path = buildString {
            append("/v1/conversations/")
            append(conversationId)
            append("/messages")
            val params = listOfNotNull(
                limit?.let { "limit=$it" },
                after?.let { "after=$it" },
                order?.let { "order=$it" },
            )
            if (params.isNotEmpty()) append(params.joinToString(prefix = "?", separator = "&"))
        }
        val response = channelTransport.adminRpc(method = "message.list", path = path, body = null)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "Iroh admin_rpc message.list failed")
        }
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(LettaMessage.serializer()), result)
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        httpGated("/v1/agents/$agentId/messages")
        throw TimelineTransportHttpException(0, "HTTP agent message reads are gated while backend is iroh://")
    }

    /**
     * letta-mobile-fe51r: on-demand full-body fetch for a tool-return message
     * that `message.list` projected down to a preview (pointer diet).
     */
    override suspend fun getToolReturn(
        conversationId: String,
        messageId: String,
    ): LettaMessage? {
        val response = channelTransport.adminRpc(
            method = "tool_return.get",
            path = "/v1/conversations/$conversationId/messages/$messageId",
            body = null,
        )
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "Iroh admin_rpc tool_return.get failed")
        }
        val result = response.result ?: return null
        return json.decodeFromJsonElement(LettaMessage.serializer(), result)
    }

    private fun httpGated(path: String) {
        if (gatedTelemetryPaths.add(path)) {
            Telemetry.event("TimelineSync", "irohMode.httpGated", "path" to path)
        }
    }

    private companion object {
        val gatedTelemetryPaths = ConcurrentHashMap.newKeySet<String>()
    }
}
