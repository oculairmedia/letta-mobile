package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
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
import kotlinx.coroutines.flow.first

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
 *
 * letta-mobile-by4vm.13: a null repository is a route the session does not serve (the desktop's tools
 * and models off Iroh): its catalog reads empty and its writes fail with that reason.
 */
class AgentEditorRepositories(
    val agents: IAgentRepository?,
    val tools: IToolRepository?,
    val models: IModelRepository?,
    val modelCatalog: StateFlow<List<LlmModel>>? = null,
)

/**
 * The editor over the session's agent, tool and model repositories. A repository the session does not
 * serve (null in [AgentEditorRepositories]) reads as an empty catalog, and its writes fail with the
 * reason ([AppServerRouteUnavailable]).
 */
class RepositoryAgentEditorBackend(
    repositories: AgentEditorRepositories,
    private val creator: AgentCreator = AgentCreator { params ->
        AgentCreated(served(repositories.agents, SessionRoute.AGENTS).createAgent(params).id.value)
    },
) : AgentEditorBackend {
    private val agentRepository = repositories.agents
    private val toolRepository = repositories.tools
    private val modelRepository = repositories.models

    override val agents: StateFlow<List<Agent>> = agentRepository?.agents ?: MutableStateFlow(emptyList())
    override val models: StateFlow<List<LlmModel>> =
        repositories.modelCatalog ?: modelRepository?.llmModels ?: MutableStateFlow(emptyList())
    override val tools: StateFlow<List<Tool>> = toolRepository?.getTools() ?: MutableStateFlow(emptyList())

    // The first emission, never the last: Android's session-scoped repository flat-maps getAgent over
    // the live session graph, so its flow never completes and waiting for its last value hangs.
    override suspend fun load(agentId: AgentId): Agent = servedAgents().getAgent(agentId).first()

    override suspend fun refreshCatalogs() {
        agentRepository?.let { refreshQuietly { it.refreshAgentsIfStale(CATALOG_MAX_AGE_MS) } }
        modelRepository?.let { refreshQuietly { it.refreshLlmModels() } }
        toolRepository?.let { refreshQuietly { it.refreshToolsIfStale(CATALOG_MAX_AGE_MS) } }
    }

    override suspend fun create(params: AgentCreateParams): AgentCreated = creator.create(params)

    override suspend fun update(agentId: AgentId, params: AgentUpdateParams) {
        servedAgents().updateAgent(agentId, params)
    }

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) = servedTools().attachTool(agentId, toolId)

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) = servedTools().detachTool(agentId, toolId)

    override suspend fun delete(agentId: AgentId) = servedAgents().deleteAgent(agentId)

    private fun servedAgents(): IAgentRepository = served(agentRepository, SessionRoute.AGENTS)

    private fun servedTools(): IToolRepository = served(toolRepository, SessionRoute.TOOLS)

    override fun reasoningEffortsFor(handle: String): List<String> {
        val model = models.value.firstOrNull { it.handle == handle || it.id == handle } ?: return emptyList()
        return if (model.enableReasoner == true || model.reasoningEffort != null) StandardEfforts else emptyList()
    }

    private companion object {
        const val CATALOG_MAX_AGE_MS = 30_000L
        val StandardEfforts = listOf("low", "medium", "high")
    }
}

/** [repository] when the session serves [route]; the backstop [AppServerRouteUnavailable] when not. */
private fun <T : Any> served(repository: T?, route: SessionRoute): T = repository ?: throw AppServerRouteUnavailable(route)

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
