package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent reads over the Iroh admin RPC control channel.
 *
 * letta-mobile-71orq: after P4 purity (letta-mobile-qfa81) the LettaApiClient
 * choke-point hard-fails any raw HTTP admin call in iroh:// mode instead of
 * silently falling back to a stale HTTP config. [AgentRepository.getAgent]
 * previously always called the raw HTTP [com.letta.mobile.data.api.AgentApi],
 * so opening a conversation over iroh:// threw
 * [com.letta.mobile.data.api.IrohAdminApiUnavailableException] before any
 * stream and the whole chat screen errored out.
 *
 * The server-side handlers already exist (AgentAdminHandlers registers
 * `agent.get` and `agent.list`); this is the missing CLIENT wiring, mirroring
 * [IrohAdminRpcConversationListSource].
 */
class IrohAdminRpcAgentSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun getAgent(id: AgentId): Agent {
        val params = buildJsonObject { put("agent_id", id.value) }
        val response = channelTransport.adminRpc(
            method = "agent.get",
            path = "/v1/agents/${id.value}",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.get failed")
        val result = response.result ?: error("Iroh admin_rpc agent.get returned no result")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    suspend fun listAgents(): List<Agent> {
        val response = channelTransport.adminRpc(
            method = "agent.list",
            path = "/v1/agents",
            body = null,
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Agent.serializer()), result)
    }
}
