package com.letta.mobile.desktop.data

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.desktop.chat.desktopChatJson
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

internal class DesktopLocalBackendAgentRepository(
    private val store: LocalBackendAdminStore,
) : IAgentRepository {
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    private val refreshingFlow = MutableStateFlow(false)
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    private var lastRefreshMs = 0L

    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow

    override suspend fun countAgents(): Int = store.countAgents() ?: 0

    override suspend fun refreshAgents() {
        refreshingFlow.value = true
        try {
            agentsFlow.value = store.listAgentsProjected(limit = 10_000, offset = 0)
                .decodeList(Agent.serializer())
            lastRefreshMs = Clock.System.now().toEpochMilliseconds()
            refreshErrorFlow.value = null
        } catch (failure: Throwable) {
            refreshErrorFlow.value = failure
            throw failure
        } finally {
            refreshingFlow.value = false
        }
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        if (agentsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshAgents()
        return true
    }

    override suspend fun listAgentSummaries(): List<AgentSummary> {
        refreshAgentsIfStale(30_000L)
        return agentsFlow.value.map { AgentSummary(it.id, it.name, it.description) }
    }

    override fun getCachedAgent(id: AgentId): Agent? = agentsFlow.value.firstOrNull { it.id == id }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        refreshAgentsIfStale(0L)
        emit(getCachedAgent(id) ?: throw NoSuchElementException("Local agent ${id.value} was not found"))
    }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview {
        val projected = store.agentContextProjected(agentId.value, conversationId?.value)
            ?: throw NoSuchElementException("Local context for agent ${agentId.value} was not found")
        return desktopChatJson.decodeFromJsonElement(ContextWindowOverview.serializer(), projected)
    }

    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported("createAgent")
    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = unsupported("updateAgent")
    override suspend fun deleteAgent(id: AgentId): Unit = unsupported("deleteAgent")
    override suspend fun exportAgent(id: AgentId): String = unsupported("exportAgent")
    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = unsupported("importAgent")
    override suspend fun attachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("attachArchive")
    override suspend fun detachArchive(agentId: AgentId, archiveId: String): Unit = unsupported("detachArchive")

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("Bundled local backend does not support $operation yet")
}

internal class DesktopLocalBackendBlockRepository(
    private val store: LocalBackendAdminStore,
) : IAgentBlockRepository {
    override suspend fun getBlocks(agentId: String): List<Block> =
        store.blocksForAgentProjected(agentId).decodeList(Block.serializer())
}

internal data class DesktopLocalRepositoryBundle(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository,
)

internal fun buildDesktopLocalRepositories(backendDirectory: File): DesktopLocalRepositoryBundle {
    val store = LocalBackendAdminStore(backendDirectory)
    return DesktopLocalRepositoryBundle(
        agentRepository = DesktopLocalBackendAgentRepository(store),
        blockRepository = DesktopLocalBackendBlockRepository(store),
    )
}

private fun <T> JsonArray?.decodeList(serializer: KSerializer<T>): List<T> =
    this?.let { desktopChatJson.decodeFromJsonElement(ListSerializer(serializer), it) }.orEmpty()
