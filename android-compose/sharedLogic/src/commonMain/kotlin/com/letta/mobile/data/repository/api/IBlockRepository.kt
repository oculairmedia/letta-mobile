package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockListParams
import com.letta.mobile.data.model.BlockUpdateParams

interface IAgentBlockRepository {
    suspend fun getBlocks(agentId: String): List<Block>
}

/**
 * Per-agent block write, addressed by agent + label. On the letta-code local
 * backend this is `block.update_agent`, which the App Server serves with
 * `write_memory_file` and a commit message, so the edit lands in the MemFS git
 * HEAD that the compiled system prompt reads. Global-id block writes fail closed
 * there, so memory editors must write through this seam.
 */
interface IAgentBlockWriteRepository : IAgentBlockRepository {
    suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block
}

interface IBlockRepository : IAgentBlockWriteRepository {
    suspend fun retrieveBlock(blockId: String): Block
    suspend fun countBlocks(): Int
    suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean = false,
        clearLimit: Boolean = false,
    ): Block
    suspend fun createBlock(params: BlockCreateParams): Block
    suspend fun deleteBlock(blockId: String)
    suspend fun attachBlock(agentId: String, blockId: String)
    suspend fun detachBlock(agentId: String, blockId: String)
    suspend fun listAllBlocks(params: BlockListParams = BlockListParams()): List<Block>
    suspend fun listAgentsForBlock(blockId: String): List<Agent>
    suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block
    suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block
}
