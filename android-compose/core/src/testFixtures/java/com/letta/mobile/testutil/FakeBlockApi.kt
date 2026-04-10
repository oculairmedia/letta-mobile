package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import io.mockk.mockk

class FakeBlockApi : BlockApi(mockk(relaxed = true)) {
    var blocks = mutableMapOf<String, MutableList<Block>>()
    var allBlocks = mutableListOf<Block>()
    var shouldFail = false
    val calls = mutableListOf<String>()
    var lastUpdateParams: BlockUpdateParams? = null

    override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        calls.add("updateAgentBlock:$agentId:$blockLabel")
        lastUpdateParams = params
        if (shouldFail) throw ApiException(500, "Server error")
        val agentBlocks = blocks.getOrPut(agentId) { mutableListOf() }
        val index = agentBlocks.indexOfFirst { it.label == blockLabel }
        val existing = agentBlocks.getOrNull(index)
        val updated = Block(
            id = existing?.id ?: "block-${blockLabel}",
            label = blockLabel,
            value = params.value ?: existing?.value ?: "",
            description = params.description ?: existing?.description,
            limit = params.limit ?: existing?.limit,
        )
        if (index >= 0) {
            agentBlocks[index] = updated
        } else {
            agentBlocks.add(updated)
        }
        return updated
    }

    override suspend fun attachBlock(agentId: String, blockId: String) {
        calls.add("attachBlock:$agentId:$blockId")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun detachBlock(agentId: String, blockId: String) {
        calls.add("detachBlock:$agentId:$blockId")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun listBlocks(agentId: String): List<Block> {
        calls.add("listBlocks:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        return blocks[agentId]?.toList() ?: emptyList()
    }

    override suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean,
        clearLimit: Boolean,
    ): Block {
        calls.add("updateGlobalBlock:$blockId:$clearDescription:$clearLimit")
        lastUpdateParams = params
        if (shouldFail) throw ApiException(500, "Server error")
        val index = allBlocks.indexOfFirst { it.id == blockId }
        val existing = allBlocks.getOrNull(index) ?: Block(id = blockId, value = params.value ?: "")
        val updated = existing.copy(
            value = params.value ?: existing.value,
            description = when {
                params.description != null -> params.description
                clearDescription -> null
                else -> existing.description
            },
            limit = when {
                params.limit != null -> params.limit
                clearLimit -> null
                else -> existing.limit
            },
        )
        if (index >= 0) {
            allBlocks[index] = updated
        } else {
            allBlocks.add(updated)
        }
        return updated
    }

    override suspend fun listAllBlocks(
        label: String?,
        isTemplate: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<Block> {
        calls.add("listAllBlocks")
        if (shouldFail) throw ApiException(500, "Server error")
        return allBlocks.filter { block ->
            (label == null || block.label == label) &&
            (isTemplate == null || block.isTemplate == isTemplate)
        }
    }
}
