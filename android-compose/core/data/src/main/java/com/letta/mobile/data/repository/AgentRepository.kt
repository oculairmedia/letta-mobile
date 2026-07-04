package com.letta.mobile.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.paging.AgentPagingSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun defaultAgentRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

open class AgentRepository(
    private val agentApi: AgentApi,
    private val agentDao: AgentDao,
    private val repositoryScope: CoroutineScope = defaultAgentRepositoryScope(),
    private val localAgentSource: LocalRuntimeAgentSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    private val transport: IChannelTransport? = null,
    // letta-mobile-71orq: Iroh admin_rpc agent reads. When the active backend
    // is iroh://, getAgent MUST route over the control channel — the raw HTTP
    // AgentApi hard-fails at the purity choke-point.
    private val irohAgentSource: IrohAdminRpcAgentSource? =
        if (transport != null && settingsRepository != null) {
            IrohAdminRpcAgentSource(transport, settingsRepository)
        } else {
            null
        },
) : IAgentRepository, BackendScopedCache {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    override open val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    override open val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshError = MutableStateFlow<Throwable?>(null)
    override open val refreshError: StateFlow<Throwable?> = _refreshError.asStateFlow()
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    init {
        repositoryScope.launch {
            try {
                val cached = agentDao.getAllOnce().map { it.toAgent() }
                if (cached.isNotEmpty()) {
                    _agents.value = cached
                }
            } catch (e: Exception) {
                Log.w("AgentRepository", "Failed to load cached agents", e)
            }
        }
        transport?.let { channelTransport ->
            repositoryScope.launch { observeAgentUpdated(channelTransport) }
            repositoryScope.launch { observeReconnects(channelTransport) }
        }
    }

    fun getAgentsPaged(tags: List<String>? = null): Flow<PagingData<Agent>> {
        return Pager(
            config = PagingConfig(
                pageSize = AgentPagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = AgentPagingSource.PAGE_SIZE * 2
            ),
            pagingSourceFactory = { AgentPagingSource(agentApi, tags) }
        ).flow
    }

    override open suspend fun countAgents(): Int = agentApi.countAgents()

    /**
     * Slim agent list for picker UIs (Schedules dropdown). Hits the
     * admin-shim's opt-in `GET /v1/agents?slim=true` so the picker pulls a
     * small `{id, name, description}` projection instead of the ~621KB full
     * agents payload. Deliberately SEPARATE from [refreshAgents]/[agents],
     * which full-object consumers (edit-agent, chat config, …) still need.
     *
     * Local-runtime mode has no remote API, so it derives summaries from the
     * on-device agents (via the shared cache / interface default).
     */
    override suspend fun listAgentSummaries(): List<AgentSummary> {
        if (isLocalRuntimeActive()) {
            return super.listAgentSummaries()
        }
        // letta-mobile-71orq: no slim admin_rpc handler exists; the raw HTTP
        // listAgentsSlim hard-fails at the purity choke-point in iroh:// mode.
        // Project summaries from the full agent.list result instead.
        if (irohAgentSource?.shouldUseIroh() == true) {
            return irohAgentSource.listAgents()
                .distinctBy { it.id }
                .map { AgentSummary(id = it.id, name = it.name, description = it.description) }
        }
        // Page through the slim projection so pickers see every agent, not
        // just the first page. Each item is tiny ({id, name, description}),
        // so this stays far below the full-agents wire cost even across pages.
        val merged = mutableListOf<AgentSummary>()
        var offset = AgentPagingSource.INITIAL_OFFSET
        while (true) {
            val page = agentApi.listAgentsSlim(limit = CACHE_REFRESH_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break
            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) break
            merged += newAgents
            if (page.size < CACHE_REFRESH_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    override open suspend fun refreshAgents() = refreshMutex.withLock {
        _isRefreshing.value = true
        try {
            refreshAgentsLocked()
            _refreshError.value = null
        } catch (t: Throwable) {
            _refreshError.value = t
            throw t
        } finally {
            _isRefreshing.value = false
        }
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            _agents.value = emptyList()
            _isRefreshing.value = false
            _refreshError.value = null
            lastRefreshAtMillis = 0L
            // Propagate DAO failure to the caller. Swallowing here would leave
            // stale agent rows from the previous backend visible after switch
            // while the in-memory state has already been cleared, which is a
            // hard-to-diagnose cross-backend leak. The orchestrator
            // (BackendSwitchInvalidator) aggregates per-cache failures so the
            // switch flow can decide what to do.
            agentDao.deleteAll()
        }
    }

    private fun isLocalRuntimeActive(): Boolean =
        localAgentSource != null && AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value)

    private suspend fun refreshAgentsLocked() {
        // Local-runtime mode: agents persist in the on-device store; the
        // Room cache is wiped per session and must be repopulated from it.
        val localSource = localAgentSource
        if (localSource != null && isLocalRuntimeActive()) {
            val local = localSource.listAgents()
            _agents.update { local }
            agentDao.insertAll(local.map { AgentEntity.fromAgent(it) })
            lastRefreshAtMillis = System.currentTimeMillis()
            return
        }
        val fresh = fetchAgentsForCache { partial ->
            _agents.update { partial }
        }
        _agents.update { fresh }
        lastRefreshAtMillis = System.currentTimeMillis()
        try {
            val entities = fresh.map { AgentEntity.fromAgent(it) }
            agentDao.insertAll(entities)
            if (fresh.isEmpty()) {
                agentDao.deleteAll()
            } else {
                agentDao.deleteExcept(fresh.map { it.id.value })
            }
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to cache agents to Room", e)
        }
    }

    private suspend fun fetchAgentsForCache(
        onProgress: suspend (List<Agent>) -> Unit = {},
    ): List<Agent> {
        // letta-mobile-71orq: in iroh:// mode the raw HTTP AgentApi hard-fails
        // at the purity choke-point, so the agents cache would stay empty and
        // conversation rows fall back to agentId.take(8). Route the list over
        // the agent.list admin_rpc handler instead. The handler returns the
        // full list, so no client-side pagination is needed here.
        if (irohAgentSource?.shouldUseIroh() == true) {
            val agents = irohAgentSource.listAgents().distinctBy { it.id }
            if (agents.isNotEmpty()) onProgress(agents)
            return agents
        }
        val merged = mutableListOf<Agent>()
        var offset = AgentPagingSource.INITIAL_OFFSET
        while (true) {
            val page = agentApi.listAgents(limit = CACHE_REFRESH_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) {
                Log.w("AgentRepository", "Agent list offset pagination made no progress; falling back to count-sized fetch")
                return fetchAgentsWithoutOffsetFallback(onProgress)
            }

            merged += newAgents
            onProgress(merged.toList())
            if (page.size < CACHE_REFRESH_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    private suspend fun fetchAgentsWithoutOffsetFallback(
        onProgress: suspend (List<Agent>) -> Unit,
    ): List<Agent> {
        val fallbackLimit = runCatching { agentApi.countAgents() }
            .getOrDefault(FALLBACK_FULL_FETCH_LIMIT)
            .coerceAtLeast(CACHE_REFRESH_PAGE_SIZE)
        val fullList = agentApi.listAgents(limit = fallbackLimit, offset = null)
            .distinctBy { it.id }
        if (fullList.isNotEmpty()) {
            onProgress(fullList)
        }
        return fullList
    }

    override open fun getCachedAgent(id: AgentId): Agent? = _agents.value.find { it.id == id }

    fun hasFreshAgents(maxAgeMs: Long): Boolean {
        return _agents.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override open suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshAgents(maxAgeMs)) return@withLock false
        _isRefreshing.value = true
        try {
            refreshAgentsLocked()
            _refreshError.value = null
        } catch (t: Throwable) {
            _refreshError.value = t
            throw t
        } finally {
            _isRefreshing.value = false
        }
        true
    }

    override open fun getAgent(id: AgentId): Flow<Agent> = flow {
        val cached = _agents.value.find { it.id == id }
        if (cached != null) {
            emit(cached)
        }
        val localSource = localAgentSource
        if (localSource != null && isLocalRuntimeActive()) {
            // No remote API for local agents; serve the durable store copy
            // when the in-memory cache missed (cold start).
            if (cached == null) {
                val stored = localSource.listAgents().find { it.id == id }
                    ?: throw NoSuchElementException("Local agent ${id.value} not found in the on-device store.")
                emit(stored)
                updateAgentInCache(stored)
            }
            return@flow
        }
        val fresh = fetchAgentRemote(id)
        emit(fresh)
        updateAgentInCache(fresh)
    }

    /**
     * Fetch a single agent from the active backend. Routes over the Iroh admin
     * RPC control channel when the backend is iroh:// (letta-mobile-71orq),
     * falling back to the raw HTTP AgentApi otherwise.
     */
    private suspend fun fetchAgentRemote(id: AgentId): Agent =
        if (irohAgentSource?.shouldUseIroh() == true) {
            irohAgentSource.getAgent(id)
        } else {
            agentApi.getAgent(id)
        }

    private suspend fun refreshAgent(agentId: AgentId): Result<Agent> = runCatching {
        val fresh = fetchAgentRemote(agentId)
        updateAgentInCache(fresh)
        // Persist the transport-driven refresh to the Room cache so a pushed
        // agent_updated change survives an app restart (CodeRabbit #517).
        runCatching { agentDao.upsert(AgentEntity.fromAgent(fresh)) }
            .onFailure { e -> Log.w("AgentRepository", "agent_updated cache persist failed for ${agentId.value}", e) }
        fresh
    }

    private suspend fun observeAgentUpdated(channelTransport: IChannelTransport) {
        channelTransport.events.collect { frame ->
            if (frame !is ServerFrame.AgentUpdated) return@collect
            val agentId = AgentId(frame.agentId)
            if (frame.reason == "deleted") {
                _agents.update { current -> current.filterNot { it.id == agentId } }
                // Targeted single-agent delete — not a broad deleteExcept that
                // could race with a stale in-memory list (CodeRabbit #517).
                runCatching { agentDao.deleteById(agentId.value) }
                    .onFailure { e -> Log.w("AgentRepository", "agent_updated delete cache update failed for ${frame.agentId}", e) }
                return@collect
            }
            // Ephemeral letta-code subagents (`agent-local-*`, transient
            // "Letta Code" workers) churn in bursts while a run fans out;
            // don't issue a per-agent GET for each one — they are not part of
            // the human agent list and the next bulk refresh reconciles them
            // (letta-mobile-vcmin).
            if (isEphemeralSubagentId(agentId)) return@collect
            refreshAgent(agentId)
                .onFailure { e -> Log.w("AgentRepository", "agent_updated refresh failed for ${frame.agentId}: ${e.message}") }
        }
    }

    private suspend fun observeReconnects(channelTransport: IChannelTransport) {
        var wasConnected: Boolean? = null
        channelTransport.state.collect { state ->
            val nowConnected = state is ChannelTransportState.Connected
            if (wasConnected == false && nowConnected) {
                // One paged list call instead of a GET /v1/agents/{id} per
                // cached agent: with ~100 cached agents (mostly ephemeral
                // `agent-local-*` subagents) the per-agent loop serialized
                // ~5s of sequential requests on every reconnect
                // (letta-mobile-vcmin).
                runCatching { refreshAgents() }
                    .onFailure { e -> Log.w("AgentRepository", "reconnect agent refresh failed: ${e.message}") }
            }
            wasConnected = nowConnected
        }
    }

    private fun isEphemeralSubagentId(id: AgentId): Boolean =
        id.value.startsWith(EPHEMERAL_SUBAGENT_ID_PREFIX)

    override open suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview {
        val localSource = localAgentSource
        if (localSource != null && isLocalRuntimeActive()) {
            // No remote API for local agents; estimate from the on-disk
            // transcript (same chars/4 heuristic letta.js uses internally).
            return localSource.contextWindowOverview(agentId) ?: ContextWindowOverview()
        }
        return agentApi.getContextWindow(agentId, conversationId)
    }

    fun getAgentPolling(id: AgentId): Flow<Agent> = flow {
        while (true) {
            val agent = fetchAgentRemote(id)
            emit(agent)
            updateAgentInCache(agent)
            delay(3000)
        }
    }

    override open suspend fun createAgent(params: AgentCreateParams): Agent {
        val agent = agentApi.createAgent(params)
        refreshAgents()
        return agent
    }

    override open suspend fun createLocalAgent(params: AgentCreateParams): Agent {
        val agent = Agent(
            id = AgentId("local-agent-${UUID.randomUUID()}"),
            name = params.name?.takeIf { it.isNotBlank() } ?: "Local Agent",
            description = params.description,
            metadata = params.metadata.orEmpty(),
            model = params.model,
            embedding = params.embedding,
            modelSettings = params.modelSettings,
            llmConfig = params.llmConfig,
            embeddingConfig = params.embeddingConfig,
            contextWindowLimit = params.contextWindowLimit,
            responseFormat = params.responseFormat,
            tags = params.tags.orEmpty(),
            system = params.system,
            enableSleeptime = params.enableSleeptime,
            agentType = params.agentType,
            messageBufferAutoclear = params.messageBufferAutoclear,
            timezone = params.timezone,
            maxFilesOpen = params.maxFilesOpen,
            perFileViewWindowCharLimit = params.perFileViewWindowCharLimit,
            hidden = params.hidden,
            compactionSettings = params.compactionSettings,
        )
        agentDao.upsert(AgentEntity.fromAgent(agent))
        updateAgentInCache(agent)
        // Room is wiped on every session-graph creation; the durable copy
        // lives in the local-runtime store (letta-mobile-y5c9u).
        localAgentSource?.persistAgent(agent)
        return agent
    }

    override open suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent {
        val cached = _agents.value.find { it.id == id }
        val preview = cached?.withUpdates(params)
        val localSource = localAgentSource
        if (localSource != null && preview != null && id.value.startsWith("local-agent-")) {
            // Local agents have no remote API even when their selected model is
            // cloud/API-backed. Persist the update locally; the routing layer
            // decides whether the next turn uses embedded runtime or remote
            // transport from the updated model/metadata binding.
            localSource.persistAgent(preview)
            agentDao.upsert(AgentEntity.fromAgent(preview))
            updateAgentInCache(preview)
            return preview
        }
        val agent = agentApi.updateAgent(id, params)
        refreshAgents()
        return agent
    }

    override open suspend fun deleteAgent(id: AgentId) {
        agentApi.deleteAgent(id)
        _agents.update { current -> current.filterNot { it.id == id } }
        try {
            agentDao.deleteExcept(_agents.value.map { it.id.value })
        } catch (e: Exception) {
            Log.w("AgentRepository", "Failed to update cached agents after delete", e)
        }
    }

    override open suspend fun exportAgent(id: AgentId): String {
        return agentApi.exportAgent(id)
    }

    override open suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse {
        val response = agentApi.importAgent(
            fileName = fileName,
            fileBytes = fileBytes,
            overrideName = overrideName,
            overrideExistingTools = overrideExistingTools,
            projectId = projectId,
            stripMessages = stripMessages,
        )
        refreshAgents()
        return response
    }

    override open suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        agentApi.attachArchive(agentId, archiveId)
        refreshAgents()
    }

    override open suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        agentApi.detachArchive(agentId, archiveId)
        refreshAgents()
    }

    private fun Agent.withUpdates(params: AgentUpdateParams): Agent = copy(
        name = params.name ?: name,
        description = params.description ?: description,
        model = params.model ?: model,
        modelSettings = params.modelSettings ?: modelSettings,
        llmConfig = params.llmConfig ?: llmConfig,
        system = params.system ?: system,
        tags = params.tags ?: tags,
        metadata = params.metadata ?: metadata,
        contextWindowLimit = params.contextWindowLimit ?: contextWindowLimit,
    )

    private fun updateAgentInCache(agent: Agent) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = agent }
            } else {
                current + agent
            }
        }
    }

    override open suspend fun checkpointAndRestoreConfig(
        agentId: AgentId,
        operation: suspend () -> Unit
    ) {
        val agent = fetchAgentRemote(agentId)
        val checkpoint = com.letta.mobile.data.model.AgentConfigCheckpoint.from(agent)
        
        try {
            operation()
        } finally {
            val updatedAgent = fetchAgentRemote(agentId)
            if (!checkpoint.matches(updatedAgent)) {
                Log.w("AgentRepository", "Agent config drift detected after operation, restoring checkpoint")
                Telemetry.event(
                    "AgentRepository",
                    "configDriftDetected",
                    "agentId" to agentId,
                    "originalModel" to checkpoint.model,
                    "driftedModel" to updatedAgent.model,
                    level = Telemetry.Level.WARN
                )
                updateAgent(agentId, checkpoint.toUpdateParams())
                Telemetry.event(
                    "AgentRepository",
                    "configRestored",
                    "agentId" to agentId,
                    "restoredModel" to checkpoint.model
                )
            }
        }
    }
    private companion object {
        const val CACHE_REFRESH_PAGE_SIZE = 50
        const val FALLBACK_FULL_FETCH_LIMIT = 5_000

        /**
         * Id prefix letta-code mints for ephemeral subagent workers (the
         * transient "Letta Code" agents that fan out during a run). Distinct
         * from the on-device `local-agent-*` prefix used by
         * [createLocalAgent].
         */
        const val EPHEMERAL_SUBAGENT_ID_PREFIX = "agent-local-"
    }
}
