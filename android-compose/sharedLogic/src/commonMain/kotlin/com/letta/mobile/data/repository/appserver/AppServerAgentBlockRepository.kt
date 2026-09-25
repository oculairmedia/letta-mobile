package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.AgentBlockTarget
import com.letta.mobile.data.repository.api.IAgentBlockWriteRepository
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.builtins.ListSerializer

class AppServerAgentBlockRepository(
    private val transport: AppServerLocalRepositoryTransport,
) : IAgentBlockWriteRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        AppServerProtocol.json.decodeFromJsonElement(
            ListSerializer(Block.serializer()),
            transport.listAgentBlocks(agentId),
        )

    override suspend fun writeAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): Block {
        val value = requireNotNull(params.value) { "block.update_agent writes the memory file contents; value is required" }
        return AppServerProtocol.json.decodeFromJsonElement(
            Block.serializer(),
            transport.updateAgentBlock(target, value),
        )
    }
}
