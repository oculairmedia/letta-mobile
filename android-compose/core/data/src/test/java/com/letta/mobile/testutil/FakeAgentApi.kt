package com.letta.mobile.testutil

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import io.mockk.mockk
import kotlinx.coroutines.delay

class FakeAgentApi : AgentApi(mockk(relaxed = true)) {
    var agents = mutableListOf<Agent>()
    var shouldFail = false
    var listDelayMillis: Long = 0L
    var failCode = 500
    var failMessage = "Server error"
    val calls = mutableListOf<String>()
    val listLimits = mutableListOf<Int?>()
    val listOffsets = mutableListOf<Int?>()
    var ignoreListOffset = false
    var exportPayloadByAgentId = mutableMapOf<String, String>()

    override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
        calls.add("listAgents")
        listLimits.add(limit)
        listOffsets.add(offset)
        if (listDelayMillis > 0L) delay(listDelayMillis)
        if (shouldFail) throw ApiException(failCode, failMessage)
        val filtered = tags?.takeIf { it.isNotEmpty() }?.let { requiredTags ->
            agents.filter { agent -> requiredTags.all { it in agent.tags } }
        } ?: agents
        val effectiveOffset = if (ignoreListOffset) 0 else offset ?: 0
        return limit?.let { filtered.drop(effectiveOffset).take(it) } ?: filtered.toList()
    }

    override suspend fun countAgents(): Int {
        calls.add("countAgents")
        if (shouldFail) throw ApiException(failCode, failMessage)
        return agents.size
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

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
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

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        calls.add("attachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        calls.add("detachArchive:$agentId:$archiveId")
        if (shouldFail) throw ApiException(failCode, failMessage)
    }
}
