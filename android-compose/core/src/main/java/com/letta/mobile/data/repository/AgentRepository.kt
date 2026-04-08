package com.letta.mobile.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.paging.AgentPagingSource
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val agentApi: AgentApi,
    private val agentDao: AgentDao,
) : IAgentRepository {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    init {
        try {
            val cached = runBlocking { agentDao.getAllOnce() }.map { it.toAgent() }
            if (cached.isNotEmpty()) {
                _agents.value = cached
            }
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to load cached agents", e)
        }
    }

    fun getAgentsPaged(tags: List<String>? = null): Flow<PagingData<Agent>> {
        return Pager(
            config = PagingConfig(
                pageSize = AgentPagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = AgentPagingSource.PAGE_SIZE * 2
            ),
            pagingSourceFactory = { AgentPagingSource(agentApi, tags) }
        ).flow
    }

    override suspend fun refreshAgents() {
        val fresh = agentApi.listAgents(limit = 1000)
        _agents.update { fresh }
        try {
            val entities = fresh.map { AgentEntity.fromAgent(it) }
            agentDao.insertAll(entities)
            agentDao.deleteExcept(fresh.map { it.id })
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to cache agents to Room", e)
        }
    }

    override fun getAgent(id: String): Flow<Agent> = flow {
        val cached = _agents.value.find { it.id == id }
        if (cached != null) {
            emit(cached)
        }
        val fresh = agentApi.getAgent(id)
        emit(fresh)
        updateAgentInCache(fresh)
    }

    fun getAgentPolling(id: String): Flow<Agent> = flow {
        while (true) {
            val agent = agentApi.getAgent(id)
            emit(agent)
            updateAgentInCache(agent)
            delay(3000)
        }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val agent = agentApi.createAgent(params)
        refreshAgents()
        return agent
    }

    override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
        val agent = agentApi.updateAgent(id, params)
        refreshAgents()
        return agent
    }

    override suspend fun deleteAgent(id: String) {
        agentApi.deleteAgent(id)
        refreshAgents()
    }

    override suspend fun exportAgent(id: String): String {
        return agentApi.exportAgent(id)
    }

    private fun updateAgentInCache(agent: Agent) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = agent }
            } else {
                current + agent
            }
        }
    }
}
