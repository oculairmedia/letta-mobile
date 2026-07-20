package com.letta.mobile.testutil

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import io.mockk.mockk

class FakeAgentApi : AgentApi(mockk(relaxed = true)) {
    var agents = mutableListOf<Agent>()
    var shouldFail = false
    var failCode = 500
    var failMessage = "Server error"
    val calls = mutableListOf<String>()
    var exportPayloadByAgentId = mutableMapOf<String, String>()

    override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
        calls.add("listAgents")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return agents.toList()
    }

    override suspend fun listAgentsSlim(limit: Int?, offset: Int?, tags: List<String>?): List<AgentSummary> {
        calls.add("listAgentsSlim")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return agents.map { AgentSummary(id = it.id, name = it.name, description = it.description) }
    }

    override suspend fun getAgent(agentId: AgentId): Agent {
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

    override suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent {
        calls.add("updateAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        val index = agents.indexOfFirst { it.id == agentId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = agents[index].copy(name = params.name ?: agents[index].name)
        agents[index] = updated
        return updated
    }

    override suspend fun deleteAgent(agentId: AgentId) {
        calls.add("deleteAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        agents.removeAll { it.id == agentId }
    }

    override suspend fun exportAgent(agentId: AgentId): String {
        calls.add("exportAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return exportPayloadByAgentId[agentId.value]
            ?: "{\"agents\":[{\"id\":\"${agentId.value}\",\"name\":\"${agents.find { it.id == agentId }?.name ?: "Agent"}\"}]}"
    }

    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse {
        calls.add(
            buildString {
                append("importAgent:")
                append(params.fileName)
                append(":")
                append(params.overrideName ?: "")
                append(":")
                append(params.overrideExistingTools?.toString() ?: "")
                append(":")
                append(params.stripMessages?.toString() ?: "")
            }
        )
        if (shouldFail) throw ApiException(failCode, failMessage)
        val importedId = "imported-${agents.size}"
        val importedName = params.overrideName ?: "Imported Agent"
        agents.add(TestData.agent(id = importedId, name = importedName))
        return ImportedAgentsResponse(agentIds = listOf(importedId))
    }

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        calls.add("attachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        calls.add("detachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }
}
