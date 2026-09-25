package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.IAgentBlockWriteRepository

/** Per-agent memory blocks over the authoritative Iroh admin RPC path. */
class IrohAgentBlockRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IAgentBlockWriteRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        directory().listAgentBlocks(AgentId(agentId))

    override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block =
        directory().updateAgentBlock(AgentId(agentId), blockLabel, params)

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for memory blocks")
}
