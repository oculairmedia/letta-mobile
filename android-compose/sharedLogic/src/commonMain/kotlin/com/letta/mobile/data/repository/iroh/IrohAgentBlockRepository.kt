package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.AgentBlockTarget
import com.letta.mobile.data.repository.api.IAgentBlockWriteRepository

/** Per-agent memory blocks over the authoritative Iroh admin RPC path. */
class IrohAgentBlockRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IAgentBlockWriteRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        directory().listAgentBlocks(AgentId(agentId))

    override suspend fun writeAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): Block =
        directory().updateAgentBlock(target, params)

    override suspend fun createAgentBlock(target: AgentBlockTarget, value: String): Block =
        directory().createAgentBlock(target, value)

    override suspend fun deleteAgentBlock(target: AgentBlockTarget) =
        directory().deleteAgentBlock(target)

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for memory blocks")
}
