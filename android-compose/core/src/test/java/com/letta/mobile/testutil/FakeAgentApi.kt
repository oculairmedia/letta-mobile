package com.letta.mobile.testutil

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams

class FakeAgentApi : AgentApi(null!!) {
    var agents = mutableListOf<Agent>()
    var shouldFail = false
    var failCode = 500
    var failMessage = "Server error"
    val calls = mutableListOf<String>()

    override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
        calls.add("listAgents")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return agents.toList()
    }

    override suspend fun getAgent(agentId: String): Agent {
        calls.add("getAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return agents.find { it.id == agentId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        calls.add("createAgent:${params.name}")
        if (shouldFail) throw ApiException(failCode, failMessage)
        val agent = TestData.agent(id = "new-${agents.size}", name = params.name ?: "Unnamed")
        agents.add(agent)
        return agent
    }

    override suspend fun updateAgent(agentId: String, params: AgentUpdateParams): Agent {
        calls.add("updateAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        val index = agents.indexOfFirst { it.id == agentId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = agents[index].copy(name = params.name ?: agents[index].name)
        agents[index] = updated
        return updated
    }

    override suspend fun deleteAgent(agentId: String) {
        calls.add("deleteAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        agents.removeAll { it.id == agentId }
    }
}
