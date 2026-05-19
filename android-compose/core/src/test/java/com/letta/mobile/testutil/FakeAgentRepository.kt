package com.letta.mobile.testutil

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Hand-written test double for [IAgentRepository]. Tests use this instead
 * of mocking the stateful concrete [com.letta.mobile.data.repository.AgentRepository]
 * so MockK bytecode instrumentation cannot leak repository state across a
 * reused Gradle daemon JVM.
 */
class FakeAgentRepository(
    initialAgents: List<Agent> = emptyList(),
) : IAgentRepository {
    val agentsState: MutableStateFlow<List<Agent>> = MutableStateFlow(initialAgents)
    override val agents: StateFlow<List<Agent>> = agentsState.asStateFlow()

    val calls: MutableList<String> = mutableListOf()

    override suspend fun countAgents(): Int {
        calls += "countAgents"
        return agentsState.value.size
    }

    override suspend fun refreshAgents() {
        calls += "refreshAgents"
    }

    override fun getCachedAgent(id: String): Agent? {
        calls += "getCachedAgent:$id"
        return agentsState.value.find { it.id == AgentId(id) }
    }

    override fun getAgent(id: String): Flow<Agent> = flow {
        calls += "getAgent:$id"
        emit(requireAgent(id))
    }

    override suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit) {
        calls += "checkpointAndRestoreConfig:$agentId"
        operation()
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        calls += "createAgent:${params.name.orEmpty()}"
        val agent = TestData.agent(
            id = "agent-${agentsState.value.size + 1}",
            name = params.name ?: "New Agent",
            model = params.model,
            description = params.description,
            tags = params.tags.orEmpty(),
        )
        agentsState.value = agentsState.value + agent
        return agent
    }

    override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
        calls += "updateAgent:$id"
        val current = requireAgent(id)
        val updated = current.copy(
            name = params.name ?: current.name,
            description = params.description ?: current.description,
            model = params.model ?: current.model,
            tags = params.tags ?: current.tags,
        )
        agentsState.value = agentsState.value.map { agent -> if (agent.id == AgentId(id)) updated else agent }
        return updated
    }

    override suspend fun deleteAgent(id: String) {
        calls += "deleteAgent:$id"
        agentsState.value = agentsState.value.filterNot { it.id == AgentId(id) }
    }

    override suspend fun exportAgent(id: String): String {
        calls += "exportAgent:$id"
        return "{\"agents\":[{\"id\":\"$id\"}]}"
    }

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: String?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse {
        calls += "importAgent:$fileName"
        val importedId = "imported-${agentsState.value.size + 1}"
        agentsState.value = agentsState.value + TestData.agent(id = importedId, name = overrideName ?: "Imported Agent")
        return ImportedAgentsResponse(agentIds = listOf(importedId))
    }

    override suspend fun attachArchive(agentId: String, archiveId: String) {
        calls += "attachArchive:$agentId:$archiveId"
    }

    override suspend fun detachArchive(agentId: String, archiveId: String) {
        calls += "detachArchive:$agentId:$archiveId"
    }

    private fun requireAgent(id: String): Agent =
        getCachedAgent(id) ?: error("No fake agent queued for $id")
}
