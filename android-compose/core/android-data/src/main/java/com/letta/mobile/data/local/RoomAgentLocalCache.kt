package com.letta.mobile.data.local

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.AgentLocalCache

/** Room-backed [AgentLocalCache] for [com.letta.mobile.data.repository.CachedAgentRepository]. */
class RoomAgentLocalCache(
    private val dao: AgentDao,
) : AgentLocalCache {
    override suspend fun getAllOnce(): List<Agent> = dao.getAllOnce().map { it.toAgent() }

    override suspend fun insertAll(agents: List<Agent>) {
        dao.insertAll(agents.map { AgentEntity.fromAgent(it) })
    }

    override suspend fun upsert(agent: Agent) {
        dao.upsert(AgentEntity.fromAgent(agent))
    }

    override suspend fun deleteExcept(keepIds: List<String>) {
        dao.deleteExcept(keepIds)
    }

    override suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
