package com.letta.mobile.data.repository

import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.session.BackendScopedCache
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepository @Inject constructor(
    private val blockApi: BlockApi,
) : IBlockRepository, BackendScopedCache {
    override suspend fun clearForBackendSwitch() = Unit

    override suspend fun getBlocks(agentId: String): List<Block> {
        return blockApi.listBlocks(agentId)
    }

    override suspend fun retrieveBlock(blockId: String): Block {
        return blockApi.retrieveBlock(blockId)
    }

    override suspend fun countBlocks(): Int {
        return blockApi.countBlocks()
    }

    override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        return blockApi.updateAgentBlock(agentId, blockLabel, params)
    }

    override suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean,
        clearLimit: Boolean,
    ): Block {
        return blockApi.updateGlobalBlock(blockId, params, clearDescription, clearLimit)
    }

    override suspend fun createBlock(params: BlockCreateParams): Block {
        return blockApi.createBlock(params)
    }

    override suspend fun deleteBlock(blockId: String) {
        blockApi.deleteBlock(blockId)
    }

    override suspend fun attachBlock(agentId: String, blockId: String) {
        blockApi.attachBlock(agentId, blockId)
    }

    override suspend fun detachBlock(agentId: String, blockId: String) {
        blockApi.detachBlock(agentId, blockId)
    }

    override suspend fun listAllBlocks(label: String?, isTemplate: Boolean?): List<Block> {
        return blockApi.listAllBlocks(label = label, isTemplate = isTemplate, limit = 1000)
    }

    override suspend fun listAgentsForBlock(blockId: String): List<Agent> {
        return blockApi.listAgentsForBlock(blockId = blockId, limit = 1000)
    }

    override suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block {
        return blockApi.attachIdentityToBlock(blockId, identityId)
    }

    override suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block {
        return blockApi.detachIdentityFromBlock(blockId, identityId)
    }
}
