package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.repository.api.IAgentBlockRepository

import com.letta.mobile.data.model.AgentId

/** Per-agent memory blocks over the authoritative Iroh admin RPC path. */
class IrohAgentBlockRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IAgentBlockRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        directory().listAgentBlocks(AgentId(agentId))

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for memory blocks")
}
