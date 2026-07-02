package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.stream.SseParser
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json

class IrohAdminRpcTimelineTransport(
    private val channelTransport: IChannelTransport,
    private val httpFallback: MessageApiTimelineTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = httpFallback.sendConversationMessage(conversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

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
    ): List<LettaMessage> = httpFallback.listAgentMessages(agentId, limit, order, conversationId)
}
