package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams

interface IBlockRepository {
    suspend fun getBlocks(agentId: String): List<Block>
    suspend fun updateBlock(agentId: String, blockLabel: String, value: String): Block
    suspend fun createBlock(params: BlockCreateParams): Block
    suspend fun deleteBlock(blockId: String)
    suspend fun attachBlock(agentId: String, blockId: String): Block
    suspend fun detachBlock(agentId: String, blockId: String)
}
