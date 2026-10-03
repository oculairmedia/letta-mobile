package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockListParams
import com.letta.mobile.data.model.BlockUpdateParams

interface IAgentBlockRepository {
    suspend fun getBlocks(agentId: String): List<Block>
}

/** One core-memory block addressed the way MemFS stores it: agent + label. */
data class AgentBlockTarget(val agentId: String, val label: String)

/**
 * Per-agent block write, addressed by [AgentBlockTarget]. On the letta-code
 * local backend this is `block.update_agent`, which the App Server serves with
 * `write_memory_file` and a commit message, so the edit lands in the MemFS git
 * HEAD that the compiled system prompt reads. Global-id block writes fail closed
 * there, so memory editors must write through this seam.
 */
interface IAgentBlockWriteRepository : IAgentBlockRepository {
    suspend fun writeAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): Block

    /**
     * bfooy.5: create a new block under the agent (`block.create_agent` on the
     * local backend: a new committed `memory/system/<label>.md`). Fails when the
     * label already exists rather than overwriting it.
     */
    suspend fun createAgentBlock(target: AgentBlockTarget, value: String): Block

    /** bfooy.5: delete the agent's block (`block.delete_agent`: a committed file delete). */
    suspend fun deleteAgentBlock(target: AgentBlockTarget)
}

interface IBlockRepository : IAgentBlockRepository {
    suspend fun retrieveBlock(blockId: String): Block
    suspend fun countBlocks(): Int
    suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block
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
