package com.letta.mobile.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.paging.AgentPagingSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import com.letta.mobile.data.repository.api.IAgentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val agentApi: AgentApi,
) : IAgentRepository {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

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

    suspend fun refreshAgents() {
        _agents.value = agentApi.listAgents()
    }

    fun getAgent(id: String): Flow<Agent> = flow {
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

    suspend fun createAgent(params: AgentCreateParams): Agent {
        val agent = agentApi.createAgent(params)
        refreshAgents()
        return agent
    }

    suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
        val agent = agentApi.updateAgent(id, params)
        refreshAgents()
        return agent
    }

    suspend fun deleteAgent(id: String) {
        agentApi.deleteAgent(id)
        refreshAgents()
    }

    suspend fun exportAgent(id: String): String {
        return agentApi.exportAgent(id)
    }

    private fun updateAgentInCache(agent: Agent) {
        val updatedList = _agents.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == agent.id }
        if (index >= 0) {
            updatedList[index] = agent
        } else {
            updatedList.add(agent)
        }
        _agents.value = updatedList
    }
}
