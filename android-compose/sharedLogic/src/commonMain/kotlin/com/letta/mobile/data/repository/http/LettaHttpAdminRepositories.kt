package com.letta.mobile.data.repository.http

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentConfigCheckpoint
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.util.Telemetry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val AGENT_LIST_SWEEP_CACHE_TTL_MS = 45_000L

class LettaHttpAdminRepositoryException(
    val code: Int,
    responseBody: String,
) : IllegalStateException("Desktop Letta API request failed with HTTP $code: $responseBody")

/**
 * Platform-neutral HTTP implementation of the Letta admin repository
 * contracts (agents / tools / schedules). Lives in commonMain so every host
 * (desktop, Android, future iOS) shares ONE implementation instead of each
 * platform re-implementing the same caching/TTL/error-flow/request-shaping
 * logic (letta-mobile-mqzkc). The platform supplies only the Ktor [HttpClient]
 * engine and a [nowMillis] clock.
 */
open class LettaHttpAdminRepositories(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
    private val nowMillis: () -> Long,
) : IAgentRepository, IToolRepository, IScheduleRepository, AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
    override val agents: StateFlow<List<Agent>> = agentsFlow.asStateFlow()
    private val refreshingFlow = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = refreshingFlow.asStateFlow()
    private val refreshErrorFlow = MutableStateFlow<Throwable?>(null)
    override val refreshError: StateFlow<Throwable?> = refreshErrorFlow.asStateFlow()
    private val toolsFlow = MutableStateFlow<List<Tool>>(emptyList())
    private val schedulesByAgentFlow = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())
    private val agentRefreshMutex = Mutex()
    private val toolRefreshMutex = Mutex()
    private var lastAgentRefreshAtMillis: Long = 0L
    private var lastToolRefreshAtMillis: Long = 0L

    override suspend fun countAgents(): Int =
        getJson("/v1/agents/count")

    override suspend fun refreshAgents() {
        val sharedSweep = agentRefreshMutex.isLocked
        agentRefreshMutex.withLock {
            if (agentsFlow.value.isNotEmpty() && nowMillis() - lastAgentRefreshAtMillis <= AGENT_LIST_SWEEP_CACHE_TTL_MS) {
                Telemetry.event("IrohAdminRpcAgentSource", "adminrpc.agentList.cacheHit", "count" to agentsFlow.value.size)
                if (sharedSweep) {
                    Telemetry.event("IrohAdminRpcAgentSource", "adminrpc.agentList.sweepShared", "count" to agentsFlow.value.size)
                }
                return
            }
            refreshingFlow.value = true
            try {
                val fresh = fetchAgentsForCache()
                agentsFlow.value = fresh
                lastAgentRefreshAtMillis = nowMillis()
                refreshErrorFlow.value = null
            } catch (t: Throwable) {
                refreshErrorFlow.value = t
                throw t
            } finally {
                refreshingFlow.value = false
            }
        }
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        val sharedSweep = agentRefreshMutex.isLocked
        return agentRefreshMutex.withLock {
            val effectiveMaxAgeMs = maxOf(maxAgeMs, AGENT_LIST_SWEEP_CACHE_TTL_MS)
            if (agentsFlow.value.isNotEmpty() && nowMillis() - lastAgentRefreshAtMillis <= effectiveMaxAgeMs) {
                Telemetry.event("IrohAdminRpcAgentSource", "adminrpc.agentList.cacheHit", "count" to agentsFlow.value.size)
                if (sharedSweep) {
                    Telemetry.event("IrohAdminRpcAgentSource", "adminrpc.agentList.sweepShared", "count" to agentsFlow.value.size)
                }
                return@withLock false
            }
            refreshingFlow.value = true
            try {
                val fresh = fetchAgentsForCache()
                agentsFlow.value = fresh
                lastAgentRefreshAtMillis = nowMillis()
                refreshErrorFlow.value = null
            } catch (t: Throwable) {
                refreshErrorFlow.value = t
                throw t
            } finally {
                refreshingFlow.value = false
            }
            true
        }
    }

    override fun getCachedAgent(id: AgentId): Agent? =
        agentsFlow.value.find { it.id == id }

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        val cached = getCachedAgent(id)
        if (cached != null) {
            emit(cached)
        }
        val fresh: Agent = getJson("/v1/agents/${id.value}")
        updateAgentInCache(fresh)
        if (fresh != cached) {
            emit(fresh)
        }
    }

    override suspend fun getContextWindow(
        agentId: AgentId,
        conversationId: ConversationId?,
    ): ContextWindowOverview =
        getJson("/v1/agents/${agentId.value}/context") {
            parameter("conversation_id", conversationId?.value)
        }

    override suspend fun checkpointAndRestoreConfig(
        agentId: AgentId,
        operation: suspend () -> Unit,
    ) {
        val before: Agent = getJson("/v1/agents/${agentId.value}")
        val checkpoint = AgentConfigCheckpoint.from(before)
        try {
            operation()
        } finally {
            val after: Agent = getJson("/v1/agents/${agentId.value}")
            if (!checkpoint.matches(after)) {
                updateAgent(agentId, checkpoint.toUpdateParams())
            }
        }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val agent: Agent = postJson("/v1/agents", params)
        updateAgentInCache(agent)
        return agent
    }

    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent {
        val agent: Agent = patchJson("/v1/agents/${id.value}", params)
        updateAgentInCache(agent)
        return agent
    }

    override suspend fun deleteAgent(id: AgentId) {
        deletePath("/v1/agents/${id.value}")
        agentsFlow.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun exportAgent(id: AgentId): String =
        getJson("/v1/agents/${id.value}/export")

    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse =
        throw UnsupportedOperationException("Desktop agent import is not implemented yet.")

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        patchEmpty("/v1/agents/${agentId.value}/archives/attach/$archiveId")
        refreshAgentsIfStale(0L)
    }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        patchEmpty("/v1/agents/${agentId.value}/archives/detach/$archiveId")
        refreshAgentsIfStale(0L)
    }

    override fun getTools(): StateFlow<List<Tool>> =
        toolsFlow.asStateFlow()

    override fun getAgentTools(agentId: String): Flow<List<Tool>> =
        agentsFlow.map { agents -> agents.firstOrNull { it.id.value == agentId }?.tools.orEmpty() }

    override suspend fun countTools(): Int =
        getJson("/v1/tools/count")

    override suspend fun refreshTools() = toolRefreshMutex.withLock {
        toolsFlow.value = getJson("/v1/tools")
        lastToolRefreshAtMillis = nowMillis()
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = toolRefreshMutex.withLock {
        if (toolsFlow.value.isNotEmpty() && nowMillis() - lastToolRefreshAtMillis <= maxAgeMs) {
            return@withLock false
        }
        toolsFlow.value = getJson("/v1/tools")
        lastToolRefreshAtMillis = nowMillis()
        true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
        getJson("/v1/tools") {
            parameter("limit", limit)
            parameter("offset", offset)
        }

    override suspend fun attachTool(agentId: String, toolId: String) {
        patchEmpty("/v1/agents/$agentId/tools/attach/$toolId")
        refreshAgentsIfStale(0L)
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        patchEmpty("/v1/agents/$agentId/tools/detach/$toolId")
        refreshAgentsIfStale(0L)
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val tool: Tool = putJson("/v1/tools", params)
        upsertToolInCache(tool)
        return tool
    }

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        val tool: Tool = patchJson("/v1/tools/$toolId", params)
        upsertToolInCache(tool)
        return tool
    }

    override suspend fun deleteTool(toolId: String) {
        deletePath("/v1/tools/$toolId")
        toolsFlow.update { current -> current.filterNot { it.id == ToolId(toolId) } }
    }

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> =
        schedulesByAgentFlow.asStateFlow().map { it[agentId].orEmpty() }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        val response: ScheduleListResponse = getJson("/v1/agents/$agentId/schedule") {
            parameter("limit", limit)
            parameter("after", after)
        }
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply { put(agentId, response.scheduledMessages) }
        }
    }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage =
        getJson("/v1/agents/$agentId/schedule/$scheduledMessageId")

    override suspend fun createSchedule(
        agentId: String,
        params: ScheduleCreateParams,
    ): ScheduledMessage {
        val schedule: ScheduledMessage = postJson("/v1/agents/$agentId/schedule", params)
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId).orEmpty()
                put(agentId, existing + schedule)
            }
        }
        return schedule
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        deletePath("/v1/agents/$agentId/schedule/$scheduledMessageId")
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply {
                put(agentId, get(agentId).orEmpty().filterNot { it.id == scheduledMessageId })
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun fetchAgentsForCache(): List<Agent> {
        val merged = mutableListOf<Agent>()
        var offset = 0
        while (true) {
            val page: List<Agent> = getJson("/v1/agents") {
                parameter("limit", CACHE_REFRESH_PAGE_SIZE)
                parameter("offset", offset)
            }
            if (page.isEmpty()) break

            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) {
                return fetchAgentsWithoutOffsetFallback()
            }

            merged += newAgents
            if (page.size < CACHE_REFRESH_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    private suspend fun fetchAgentsWithoutOffsetFallback(): List<Agent> {
        val fallbackLimit = runCatching { countAgents() }
            .getOrDefault(FALLBACK_FULL_FETCH_LIMIT)
            .coerceAtLeast(CACHE_REFRESH_PAGE_SIZE)
        return getJson<List<Agent>>("/v1/agents") {
            parameter("limit", fallbackLimit)
        }.distinctBy { it.id }
    }

    private fun updateAgentInCache(agent: Agent) {
        agentsFlow.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) current.toMutableList().apply { this[index] = agent } else current + agent
        }
    }

    private fun upsertToolInCache(tool: Tool) {
        toolsFlow.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
    }

    private suspend inline fun <reified T> getJson(
        path: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = httpClient.get("$baseUrl$path") {
            applyAuth()
            builder()
        }
        response.requireSuccess()
        return response.body()
    }

    private suspend inline fun <reified T> postJson(path: String, body: Any): T {
        val response = httpClient.post("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.requireSuccess()
        return response.body()
    }

    private suspend inline fun <reified T> putJson(path: String, body: Any): T {
        val response = httpClient.put("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.requireSuccess()
        return response.body()
    }

    private suspend inline fun <reified T> patchJson(path: String, body: Any): T {
        val response = httpClient.patch("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.requireSuccess()
        return response.body()
    }

    private suspend fun patchEmpty(path: String) {
        val response = httpClient.patch("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
        }
        response.requireSuccess()
    }

    private suspend fun deletePath(path: String) {
        val response = httpClient.delete("$baseUrl$path") {
            applyAuth()
        }
        response.requireSuccess()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::bearerAuth)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) {
            throw LettaHttpAdminRepositoryException(status.value, bodyAsText())
        }
    }

    private companion object {
        const val CACHE_REFRESH_PAGE_SIZE = 50
        const val FALLBACK_FULL_FETCH_LIMIT = 5_000
    }
}
