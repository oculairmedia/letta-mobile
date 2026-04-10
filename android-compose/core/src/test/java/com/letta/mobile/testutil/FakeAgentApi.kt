package com.letta.mobile.testutil

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
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

    override suspend fun exportAgent(agentId: String): String {
        calls.add("exportAgent:$agentId")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return exportPayloadByAgentId[agentId]
            ?: "{\"agents\":[{\"id\":\"$agentId\",\"name\":\"${agents.find { it.id == agentId }?.name ?: "Agent"}\"}]}"
    }

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: String?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse {
        calls.add(
            buildString {
                append("importAgent:")
                append(fileName)
                append(":")
                append(overrideName ?: "")
                append(":")
                append(overrideExistingTools?.toString() ?: "")
                append(":")
                append(stripMessages?.toString() ?: "")
            }
        )
        if (shouldFail) throw ApiException(failCode, failMessage)
        val importedId = "imported-${agents.size}"
        val importedName = overrideName ?: "Imported Agent"
        agents.add(TestData.agent(id = importedId, name = importedName))
        return ImportedAgentsResponse(agentIds = listOf(importedId))
    }

    override suspend fun attachArchive(agentId: String, archiveId: String) {
        calls.add("attachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }

    override suspend fun detachArchive(agentId: String, archiveId: String) {
        calls.add("detachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }
}
