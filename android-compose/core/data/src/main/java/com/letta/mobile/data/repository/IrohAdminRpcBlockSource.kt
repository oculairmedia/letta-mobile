package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Authoritative block total, taken from the server's paged envelope.
     *
     * One page of size 1 is enough: the envelope carries `total` for the whole
     * union, so a count never has to be inferred from how many rows the pager
     * managed to accumulate (inferring it is what produced a wrong exact count
     * against a backend that ignored limit/offset). Falls back to a full sweep
     * only when the server answered with the legacy bare array — in which case the
     * array length IS the exact total, because a bare array means "full set".
     */
    suspend fun countBlocks(): Int {
        val first = fetchPage(offset = 0, limit = 1, label = null, isTemplate = null)
        first.total?.let { return it }
        return listAllBlocks().size
    }

    /**
     * Pages `block.list` with a limit/offset cursor.
     *
     * letta-mobile post-cutover regression (2026-08-01): the store tier serves
     * block.list as the union of every agent's memory files — 1447 blocks /
     * ~1.83 MB on the live host — and a single unwindowed response exceeds the
     * 1 MiB Iroh admin_rpc frame cap, so the Block Library rendered nothing.
     * This mirrors [IrohAdminRpcAgentSource.listAgents]: small pages, dedup by id,
     * hard iteration cap so a backend that ignores `offset` cannot spin.
     *
     * `has_more` from the server's envelope is preferred over the page-size
     * heuristic; the heuristic only applies to a legacy bare-array response, where
     * a bare array already means "this is the full set".
     */
    suspend fun listAllBlocks(label: String? = null, isTemplate: Boolean? = null): List<Block> {
        val merged = mutableListOf<Block>()
        val seenIds = HashSet<String>()
        var offset = 0
        var iterations = 0
        while (iterations < MAX_BLOCK_LIST_PAGES) {
            iterations++
            val page = fetchPage(offset, BLOCK_LIST_PAGE_SIZE, label, isTemplate)
            if (page.blocks.isEmpty()) break
            // A backend that ignores BOTH limit and offset re-serves page 1
            // forever; contributing nothing new is the stop signal.
            val fresh = page.blocks.filter { block -> seenIds.add(block.id.value) }
            if (fresh.isEmpty()) break
            merged += fresh
            val hasMore = page.hasMore ?: (page.blocks.size >= BLOCK_LIST_PAGE_SIZE)
            if (!hasMore) break
            offset += page.blocks.size
        }
        return merged
    }

    private suspend fun fetchPage(
        offset: Int,
        limit: Int,
        label: String?,
        isTemplate: Boolean?,
    ): BlockPage {
        val params = buildJsonObject {
            label?.let { put("label", it) }
            isTemplate?.let { put("is_template", it) }
            put("limit", limit.toString())
            put("offset", offset.toString())
        }
        val response = channelTransport.adminRpc(
            method = "block.list",
            path = "/v1/blocks?limit=$limit&offset=$offset",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc block.list failed")
        val result = response.result ?: return BlockPage(emptyList(), total = null, hasMore = false)
        // Dual shape: a bare array means the server delivered the FULL set (legacy
        // contract), an object means it windowed and is telling us the true total.
        return when (result) {
            is JsonArray -> BlockPage(
                blocks = json.decodeFromJsonElement(ListSerializer(Block.serializer()), result),
                total = null,
                hasMore = false,
            )

            is JsonObject -> BlockPage(
                blocks = result["blocks"]
                    ?.let { json.decodeFromJsonElement(ListSerializer(Block.serializer()), it) }
                    .orEmpty(),
                total = (result["total"] as? JsonPrimitive)?.content?.toIntOrNull(),
                hasMore = (result["has_more"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            )

            else -> BlockPage(emptyList(), total = null, hasMore = false)
        }
    }

    private data class BlockPage(
        val blocks: List<Block>,
        val total: Int?,
        val hasMore: Boolean?,
    )
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

    companion object {
        /**
         * Matches `ToolAdminHandlers.DEFAULT_BLOCK_LIST_LIMIT`. Kept modest so a
         * page stays far under the ~1 MiB unchunked Iroh frame cap even when every
         * block is at its 5000-char value limit.
         */
        const val BLOCK_LIST_PAGE_SIZE = 50

        /** Belt-and-braces bound: 100 x 50 = 5000 blocks, well above the live corpus. */
        const val MAX_BLOCK_LIST_PAGES = 100
    }
}
