package com.letta.mobile.data.local

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.AgentLocalCache

/**
 * Room-backed [AgentLocalCache] for [com.letta.mobile.data.repository.CachedAgentRepository]. Every
 * access waits for the session's cache clear (letta-mobile-by4vm.94, [GatedDao]).
 */
class RoomAgentLocalCache(
    private val dao: GatedDao<AgentDao>,
) : AgentLocalCache {
    override suspend fun getAllOnce(): List<Agent> = dao.get().getAllOnce().map { it.toAgent() }

    override suspend fun insertAll(agents: List<Agent>) {
        dao.get().insertAll(agents.map { AgentEntity.fromAgent(it) })
    }

    override suspend fun upsert(agent: Agent) {
        dao.get().upsert(AgentEntity.fromAgent(agent))
    }

    override suspend fun deleteExcept(keepIds: List<String>) {
        dao.get().deleteExcept(keepIds)
    }

    override suspend fun deleteById(id: String) {
        dao.get().deleteById(id)
    }

    override suspend fun deleteAll() {
        dao.get().deleteAll()
    }
}
