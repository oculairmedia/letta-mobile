package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.BlockIrohSource
import com.letta.mobile.data.repository.api.BlockRemoteSource
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.session.BackendScopedCache

/**
 * Phase 5e: platform-neutral block repository (HTTP / Iroh routing). Android
 * supplies [BlockRemoteSource] and optional [BlockIrohSource]. No durable local
 * cache — identity attach/detach stay HTTP-only (no Iroh handlers yet).
 *
 * Desktop continues to use [com.letta.mobile.data.repository.iroh.IrohAgentBlockRepository]
 * / HTTP admin for its session graph.
 */
open class CachedBlockRepository(
    private val remote: BlockRemoteSource,
    private val irohBlockSource: BlockIrohSource? = null,
) : IBlockRepository, BackendScopedCache {
    override suspend fun clearForBackendSwitch() = Unit

    override suspend fun getBlocks(agentId: String): List<Block> {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.listAgentBlocks(agentId)
        }
        return remote.listBlocks(agentId)
    }

    override suspend fun retrieveBlock(blockId: String): Block {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.retrieveBlock(blockId)
        }
        return remote.retrieveBlock(blockId)
    }

    /**
     * In iroh:// mode the HTTP admin route is hard-failed at the LettaApiClient
     * choke-point, so this must go through admin_rpc. The paged `block.list`
     * envelope carries an authoritative `total`, which is a truthful exact count —
     * unlike inferring one from however many rows a pager managed to accumulate.
     */
    override suspend fun countBlocks(): Int {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.countBlocks()
        }
        return remote.countBlocks()
    }

    override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.updateAgentBlock(agentId, blockLabel, params)
        }
        return remote.updateAgentBlock(agentId, blockLabel, params)
    }

    override suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean,
        clearLimit: Boolean,
    ): Block {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.updateGlobalBlock(blockId, params, clearDescription, clearLimit)
        }
        return remote.updateGlobalBlock(blockId, params, clearDescription, clearLimit)
    }

    override suspend fun createBlock(params: BlockCreateParams): Block {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.createBlock(params)
        }
        return remote.createBlock(params)
    }

    override suspend fun deleteBlock(blockId: String) {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.deleteBlock(blockId)
            return
        }
        remote.deleteBlock(blockId)
    }

    override suspend fun attachBlock(agentId: String, blockId: String) {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.attachBlock(agentId, blockId)
            return
        }
        remote.attachBlock(agentId, blockId)
    }

    override suspend fun detachBlock(agentId: String, blockId: String) {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.detachBlock(agentId, blockId)
            return
        }
        remote.detachBlock(agentId, blockId)
    }

    override suspend fun listAllBlocks(label: String?, isTemplate: Boolean?): List<Block> {
        val irohSource = irohBlockSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.listAllBlocks(label, isTemplate)
        }
        return exhaustPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, offset ->
                remote.listAllBlocks(
                    label = label,
                    isTemplate = isTemplate,
                    limit = limit,
                    offset = offset,
                )
            },
            dedupKey = { block -> block.id.value },
        )
    }

    override suspend fun listAgentsForBlock(blockId: String): List<Agent> {
        return exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listAgentsForBlock(
                    blockId = blockId,
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                )
            },
            extractCursor = { agent -> agent.id.value },
            dedupKey = { agent -> agent.id.value },
        )
    }

    override suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block {
        return remote.attachIdentityToBlock(blockId, identityId)
    }

    override suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block {
        return remote.detachIdentityFromBlock(blockId, identityId)
    }
}
