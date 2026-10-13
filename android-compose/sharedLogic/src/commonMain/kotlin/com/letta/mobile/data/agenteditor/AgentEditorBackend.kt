package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.last

/**
 * What the agent editor reads and writes. Agent CRUD goes through the session's App Server
 * repositories (admin_rpc over Iroh or the local runtime); a platform binds only what differs, which
 * is how a new agent is created ([create]: Android binds an agent to its on-device runtime, the
 * desktop opens the new agent's first conversation).
 */
interface AgentEditorBackend {
    val agents: StateFlow<List<Agent>>
    val models: StateFlow<List<LlmModel>>
    val tools: StateFlow<List<Tool>>

    /** The agent as the server has it now. */
    suspend fun load(agentId: AgentId): Agent

    /** Refreshes the roster, models and tools the editor offers; a failure leaves what was there. */
    suspend fun refreshCatalogs()

    suspend fun create(params: AgentCreateParams): AgentCreated
    suspend fun update(agentId: AgentId, params: AgentUpdateParams)
    suspend fun attachTool(agentId: AgentId, toolId: ToolId)
    suspend fun detachTool(agentId: AgentId, toolId: ToolId)
    suspend fun delete(agentId: AgentId)

    /** The named reasoning efforts [handle] accepts; empty when it has none or the backend cannot say. */
    fun reasoningEffortsFor(handle: String): List<String> = emptyList()
}

/** A platform's own way to create an agent; the default creates it on the session's repository. */
fun interface AgentCreator {
    suspend fun create(params: AgentCreateParams): AgentCreated
}

/**
 * The session repositories the editor works over. [modelCatalog] replaces [models]' list where the
 * host already holds the models its chat offers (the desktop's chat controller).
 */
class AgentEditorRepositories(
    val agents: IAgentRepository,
    val tools: IToolRepository,
    val models: IModelRepository,
    val modelCatalog: StateFlow<List<LlmModel>>? = null,
)

/**
 * The editor over the session's agent, tool and model repositories. A repository a backend does not
 * bind (the desktop's tools off Iroh) reads as an empty catalog, and its writes fail with the reason.
 */
class RepositoryAgentEditorBackend(
    repositories: AgentEditorRepositories,
    private val creator: AgentCreator = AgentCreator { params -> AgentCreated(repositories.agents.createAgent(params).id.value) },
) : AgentEditorBackend {
    private val agentRepository = repositories.agents
    private val toolRepository = repositories.tools
    private val modelRepository = repositories.models

    override val agents: StateFlow<List<Agent>> = catalogOrEmpty { agentRepository.agents }
    override val models: StateFlow<List<LlmModel>> = repositories.modelCatalog ?: catalogOrEmpty { modelRepository.llmModels }
    override val tools: StateFlow<List<Tool>> = catalogOrEmpty { toolRepository.getTools() }

    // The flow's last emission re-fetches, so an agent edited elsewhere is never shown stale.
    override suspend fun load(agentId: AgentId): Agent = agentRepository.getAgent(agentId).last()

    override suspend fun refreshCatalogs() {
        refreshQuietly { agentRepository.refreshAgentsIfStale(CATALOG_MAX_AGE_MS) }
        refreshQuietly { modelRepository.refreshLlmModels() }
        refreshQuietly { toolRepository.refreshToolsIfStale(CATALOG_MAX_AGE_MS) }
    }

    override suspend fun create(params: AgentCreateParams): AgentCreated = creator.create(params)

    override suspend fun update(agentId: AgentId, params: AgentUpdateParams) {
        agentRepository.updateAgent(agentId, params)
    }

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) = toolRepository.attachTool(agentId, toolId)

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) = toolRepository.detachTool(agentId, toolId)

    override suspend fun delete(agentId: AgentId) = agentRepository.deleteAgent(agentId)

    override fun reasoningEffortsFor(handle: String): List<String> {
        val model = models.value.firstOrNull { it.handle == handle || it.id == handle } ?: return emptyList()
        return if (model.enableReasoner == true || model.reasoningEffort != null) StandardEfforts else emptyList()
    }

    private companion object {
        const val CATALOG_MAX_AGE_MS = 30_000L
        val StandardEfforts = listOf("low", "medium", "high")
    }
}

/** [read]'s catalog, or an empty one where the backend does not bind that repository. */
private fun <T> catalogOrEmpty(read: () -> StateFlow<List<T>>): StateFlow<List<T>> = try {
    read()
} catch (_: UnsupportedOperationException) {
    MutableStateFlow(emptyList())
}

/** Runs [block], rethrowing cancellation and swallowing anything else (a refresh that may fail). */
private suspend fun refreshQuietly(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // A catalog that fails to refresh keeps what it had; the page shows what is there.
    }
}
