package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Block reads/writes over the Iroh admin RPC control channel.
 *
 * P4 purity batch (letta-mobile client-side Iroh gaps): the LettaApiClient
 * choke-point hard-fails raw HTTP admin calls in iroh:// mode. The server-side
 * handlers already exist (ToolAdminHandlers registers block.get, block.create,
 * block.update, block.delete, block.list); this is the missing CLIENT wiring.
 */
class IrohAdminRpcBlockSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    suspend fun retrieveBlock(blockId: String): Block {
        val params = buildJsonObject { put("block_id", blockId) }
        val response = channelTransport.adminRpc(
            method = "block.get",
            path = "/v1/blocks/$blockId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.get failed")
        val result = response.result ?: error("Iroh admin_rpc block.get returned no result")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean = false,
        clearLimit: Boolean = false,
    ): Block {
        val requestBody = buildJsonObject {
            put("block_id", blockId)
            params.value?.let { put("value", it) }
            when {
                params.description != null -> put("description", params.description)
                clearDescription -> put("description", JsonNull)
            }
            when {
                params.limit != null -> put("limit", params.limit)
                clearLimit -> put("limit", JsonNull)
            }
        }
        val response = channelTransport.adminRpc(
            method = "block.update",
            path = "/v1/blocks/$blockId",
            body = requestBody.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.update failed")
        val result = response.result ?: error("Iroh admin_rpc block.update returned no result")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun createBlock(params: BlockCreateParams): Block {
        val response = channelTransport.adminRpc(
            method = "block.create",
            path = "/v1/blocks",
            body = json.encodeToString(BlockCreateParams.serializer(), params),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.create failed")
        val result = response.result ?: error("Iroh admin_rpc block.create returned no result")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun deleteBlock(blockId: String) {
        val params = buildJsonObject { put("block_id", blockId) }
        val response = channelTransport.adminRpc(
            method = "block.delete",
            path = "/v1/blocks/$blockId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.delete failed")
    }

    suspend fun listAllBlocks(label: String? = null, isTemplate: Boolean? = null): List<Block> {
        val params = buildJsonObject {
            label?.let { put("label", it) }
            isTemplate?.let { put("is_template", it) }
        }
        val response = channelTransport.adminRpc(
            method = "block.list",
            path = "/v1/blocks",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Block.serializer()), result)
    }
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
