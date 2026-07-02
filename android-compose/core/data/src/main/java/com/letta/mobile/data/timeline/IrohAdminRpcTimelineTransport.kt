package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
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
        ensureConnectedForAdminRpc()
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

    private suspend fun ensureConnectedForAdminRpc() {
        if (channelTransport.state.value is ChannelTransportState.Connected) return
        val config = settingsRepository.activeConfig.value
            ?: error("Iroh admin_rpc requested with no active backend config")
        val serverUrl = config.serverUrl
        if (!IrohChannelTransport.shouldUseIroh(serverUrl)) {
            error("Iroh admin_rpc requested while backend is not iroh://")
        }
        Telemetry.event(
            "IrohTransport", "admin_rpc.ensureConnected",
            "serverUrl" to serverUrl,
            "state" to channelTransport.state.value::class.simpleName,
        )
        channelTransport.connect(
            baseShimUrl = serverUrl,
            token = config.accessToken.orEmpty(),
            deviceId = "android-letta-mobile",
            clientVersion = "android-iroh-admin-rpc",
        )
        if (channelTransport.state.value !is ChannelTransportState.Connected) {
            error("Iroh admin_rpc could not connect transport")
        }
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
