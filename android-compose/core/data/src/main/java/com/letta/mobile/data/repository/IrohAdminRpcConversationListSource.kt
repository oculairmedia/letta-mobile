package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.ChannelTransportState
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
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun listConversations(
        agentId: AgentId?,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation> {
        ensureConnectedForAdminRpc()
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

    private suspend fun ensureConnectedForAdminRpc() {
        if (channelTransport.state.value is ChannelTransportState.Connected) return
        val config = settingsRepository.activeConfig.value
            ?: error("Iroh conversation.list requested with no active backend config")
        val serverUrl = config.serverUrl
        if (!IrohChannelTransport.shouldUseIroh(serverUrl)) {
            error("Iroh conversation.list requested while backend is not iroh://")
        }
        Telemetry.event(
            "IrohTransport", "conversation_list.ensureConnected",
            "serverUrl" to serverUrl,
            "state" to channelTransport.state.value::class.simpleName,
        )
        channelTransport.connect(
            baseShimUrl = serverUrl,
            token = config.accessToken.orEmpty(),
            deviceId = "android-letta-mobile",
            clientVersion = "android-iroh-conversation-list",
        )
    }
}
