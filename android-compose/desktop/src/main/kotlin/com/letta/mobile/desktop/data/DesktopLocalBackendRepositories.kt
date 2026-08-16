package com.letta.mobile.desktop.data

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
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientBinding
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DesktopLocalBackendAgentRepository(
    private val clientBinding: DesktopLocalAppServerClientBinding,
) : IAgentRepository {
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    private val refreshingFlow = MutableStateFlow(false)
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    private var lastRefreshMs = 0L

    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow

    override suspend fun countAgents(): Int {
        refreshAgentsIfStale(30_000L)
        return agentsFlow.value.size
    }

    override suspend fun refreshAgents() {
        refreshingFlow.value = true
        try {
            val response = clientBinding.client().agentList(
                AppServerCommand.AgentList(
                    requestId = requestId("agent-list"),
                    query = buildJsonObject {
                        put("limit", "10000")
                        put("offset", "0")
                    },
                ),
            )
            check(response.success) { response.error ?: "Bundled App Server agent listing failed" }
            val rows = response.agents ?: error("Bundled App Server agent listing returned no agents")
            agentsFlow.value = rows.decodeList(Agent.serializer())
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
        val response = clientBinding.client().adminRpc(
            AppServerCommand.AdminRpc(
                requestId = requestId("agent-context"),
                method = "agent.context",
                params = buildJsonObject {
                    put("agent_id", agentId.value)
                    conversationId?.value?.let { put("conversation_id", it) }
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server agent context failed" }
        val projected = response.result as? JsonObject
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

    private fun requestId(operation: String): String = "desktop-local-$operation-${UUID.randomUUID()}"
}

internal class DesktopLocalBackendBlockRepository(
    private val clientBinding: DesktopLocalAppServerClientBinding,
) : IAgentBlockRepository {
    override suspend fun getBlocks(agentId: String): List<Block> {
        val response = clientBinding.client().adminRpc(
            AppServerCommand.AdminRpc(
                requestId = "desktop-local-block-list-${UUID.randomUUID()}",
                method = "block.list_agent",
                params = buildJsonObject {
                    put("agent_id", agentId)
                    put("limit", "10000")
                    put("offset", "0")
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server block listing failed" }
        val rows = when (val result = response.result) {
            is JsonArray -> result
            is JsonObject -> result["blocks"] as? JsonArray
                ?: error("Bundled App Server block listing returned no blocks")
            null -> error("Bundled App Server block listing returned no result")
            else -> error("Bundled App Server block listing returned an unsupported result")
        }
        return rows.decodeList(Block.serializer())
    }
}

internal data class DesktopLocalRepositoryBundle(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository,
)

internal fun buildDesktopLocalRepositories(
    clientProvider: (suspend () -> AppServerClient)? = null,
): DesktopLocalRepositoryBundle {
    val baselineGeneration = DesktopLocalAppServerClientRegistry.generation()
    val binding = DesktopLocalAppServerClientBinding(
        clientProvider ?: { DesktopLocalAppServerClientRegistry.awaitClientAfter(baselineGeneration) },
    )
    return DesktopLocalRepositoryBundle(
        agentRepository = DesktopLocalBackendAgentRepository(binding),
        blockRepository = DesktopLocalBackendBlockRepository(binding),
    )
}

private fun <T> JsonArray?.decodeList(serializer: KSerializer<T>): List<T> =
    this?.let { desktopChatJson.decodeFromJsonElement(ListSerializer(serializer), it) }.orEmpty()
