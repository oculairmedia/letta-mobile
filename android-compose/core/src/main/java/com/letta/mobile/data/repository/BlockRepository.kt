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
    override suspend fun getBlocks(agentId: String): List<Block> {
        return blockApi.listBlocks(agentId)
    }

    override suspend fun updateBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        return blockApi.updateBlock(agentId, blockLabel, params)
    }

    override suspend fun createBlock(params: BlockCreateParams): Block {
        return blockApi.createBlock(params)
    }

    override suspend fun deleteBlock(blockId: String) {
        blockApi.deleteBlock(blockId)
    }

    override suspend fun attachBlock(agentId: String, blockId: String): Block {
        return blockApi.attachBlock(agentId, blockId)
    }

    override suspend fun detachBlock(agentId: String, blockId: String) {
        blockApi.detachBlock(agentId, blockId)
    }
}
