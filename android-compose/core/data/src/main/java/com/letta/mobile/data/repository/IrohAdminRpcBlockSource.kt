package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcBlockSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false },
) {
    fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun attachBlock(agentId: String, blockId: String) {
        val response = channelTransport.adminRpc(
            method = "block.attach",
            path = "/v1/agents/$agentId/core-memory/blocks/attach/$blockId",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("block_id", blockId)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.attach failed")
    }

    suspend fun detachBlock(agentId: String, blockId: String) {
        val response = channelTransport.adminRpc(
            method = "block.detach",
            path = "/v1/agents/$agentId/core-memory/blocks/detach/$blockId",
            body = buildJsonObject {
                put("agent_id", agentId)
                put("block_id", blockId)
            }.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.detach failed")
    }

    suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("label", blockLabel)
            params.value?.let { put("value", it) }
            params.limit?.let { put("limit", it) }
            params.description?.let { put("description", it) }
        }
        val response = channelTransport.adminRpc(
            method = "block.update_agent",
            path = "/v1/agents/$agentId/core-memory/blocks/$blockLabel",
            body = body.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.update_agent failed")
        val result = response.result ?: error("Iroh admin_rpc block.update_agent returned no result")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }
}
