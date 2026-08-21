package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams

/**
 * Remote HTTP (or equivalent) block admin surface used by
 * [com.letta.mobile.data.repository.CachedBlockRepository].
 * Platform modules supply Ktor/[BlockApi] bindings; Iroh traffic goes through
 * [BlockIrohSource].
 */
interface BlockRemoteSource {
    suspend fun listBlocks(agentId: String): List<Block>
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
    suspend fun listAllBlocks(
        label: String? = null,
        isTemplate: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<Block>

    suspend fun listAgentsForBlock(
        blockId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Agent>

    suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block
    suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block
}

/**
 * Iroh admin_rpc block surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcBlockSource].
 *
 * Distinct from desktop [com.letta.mobile.data.repository.iroh.IrohAgentBlockRepository].
 */
interface BlockIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listAgentBlocks(agentId: String): List<Block>
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
    suspend fun listAllBlocks(label: String? = null, isTemplate: Boolean? = null): List<Block>
}
