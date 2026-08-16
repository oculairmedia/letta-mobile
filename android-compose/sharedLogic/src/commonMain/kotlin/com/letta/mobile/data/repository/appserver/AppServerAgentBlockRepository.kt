package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.builtins.ListSerializer

class AppServerAgentBlockRepository(
    private val transport: AppServerLocalRepositoryTransport,
) : IAgentBlockRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        AppServerProtocol.json.decodeFromJsonElement(
            ListSerializer(Block.serializer()),
            transport.listAgentBlocks(agentId),
        )
}
