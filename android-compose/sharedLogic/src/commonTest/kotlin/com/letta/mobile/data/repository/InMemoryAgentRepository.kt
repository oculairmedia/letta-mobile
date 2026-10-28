package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository

/** In-memory test repositories hold full agents; there is no separate remote/list projection. */
internal abstract class InMemoryAgentRepository : IAgentRepository {
    override suspend fun getFreshAgent(id: AgentId, force: Boolean): Agent =
        getCachedAgent(id) ?: error("No agent ${id.value}")
}
