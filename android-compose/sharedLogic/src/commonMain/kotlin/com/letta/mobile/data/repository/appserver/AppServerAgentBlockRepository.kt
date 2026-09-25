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

    override suspend fun writeAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): Block =
        AppServerProtocol.json.decodeFromJsonElement(
            Block.serializer(),
            transport.updateAgentBlock(target, params),
        )

    override suspend fun createAgentBlock(target: AgentBlockTarget, value: String): Block =
        AppServerProtocol.json.decodeFromJsonElement(
            Block.serializer(),
            transport.createAgentBlock(target, value),
        )

    override suspend fun deleteAgentBlock(target: AgentBlockTarget) =
        transport.deleteAgentBlock(target)
}
