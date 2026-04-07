package com.letta.mobile.data.repository

import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.IBlockRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepository @Inject constructor(
    private val blockApi: BlockApi,
) : IBlockRepository {
    suspend fun getBlocks(agentId: String): List<Block> {
        return blockApi.listBlocks(agentId)
    }

    suspend fun updateBlock(agentId: String, blockLabel: String, value: String): Block {
        val params = BlockUpdateParams(value = value)
        return blockApi.updateBlock(agentId, blockLabel, params)
    }

    suspend fun createBlock(params: BlockCreateParams): Block {
        return blockApi.createBlock(params)
    }

    suspend fun deleteBlock(blockId: String) {
        blockApi.deleteBlock(blockId)
    }

    suspend fun attachBlock(agentId: String, blockId: String): Block {
        return blockApi.attachBlock(agentId, blockId)
    }

    suspend fun detachBlock(agentId: String, blockId: String) {
        blockApi.detachBlock(agentId, blockId)
    }
}
