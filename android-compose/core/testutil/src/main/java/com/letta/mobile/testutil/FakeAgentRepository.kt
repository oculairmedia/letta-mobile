package com.letta.mobile.testutil

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
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

    val isRefreshingState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = isRefreshingState.asStateFlow()

    val refreshErrorState: MutableStateFlow<Throwable?> = MutableStateFlow(null)
    override val refreshError: StateFlow<Throwable?> = refreshErrorState.asStateFlow()

    val calls: MutableList<String> = mutableListOf()

    override suspend fun countAgents(): Int {
        calls += "countAgents"
        return agentsState.value.size
    }

    override suspend fun refreshAgents() {
        calls += "refreshAgents"
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        calls += "refreshAgentsIfStale:$maxAgeMs"
        return false
    }

    override fun getCachedAgent(id: AgentId): Agent? {
        calls += "getCachedAgent:${id.value}"
        return agentsState.value.find { it.id == id }
    }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        calls += "getAgent:${id.value}"
        emit(requireAgent(id))
    }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview {
        calls += "getContextWindow:${agentId.value}:${conversationId?.value ?: "<null>"}"
        return ContextWindowOverview()
    }

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) {
        calls += "checkpointAndRestoreConfig:${agentId.value}"
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

    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent {
        calls += "updateAgent:${id.value}"
        val current = requireAgent(id)
        val updated = current.copy(
            name = params.name ?: current.name,
            description = params.description ?: current.description,
            model = params.model ?: current.model,
            tags = params.tags ?: current.tags,
        )
        agentsState.value = agentsState.value.map { agent -> if (agent.id == id) updated else agent }
        return updated
    }

    override suspend fun deleteAgent(id: AgentId) {
        calls += "deleteAgent:${id.value}"
        agentsState.value = agentsState.value.filterNot { it.id == id }
    }

    override suspend fun exportAgent(id: AgentId): String {
        calls += "exportAgent:${id.value}"
        return "{\"agents\":[{\"id\":\"${id.value}\"}]}"
    }

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse {
        calls += "importAgent:$fileName"
        val importedId = "imported-${agentsState.value.size + 1}"
        agentsState.value = agentsState.value + TestData.agent(id = importedId, name = overrideName ?: "Imported Agent")
        return ImportedAgentsResponse(agentIds = listOf(importedId))
    }

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        calls += "attachArchive:${agentId.value}:$archiveId"
    }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        calls += "detachArchive:${agentId.value}:$archiveId"
    }

    private fun requireAgent(id: AgentId): Agent =
        getCachedAgent(id) ?: error("No fake agent queued for ${id.value}")
}
