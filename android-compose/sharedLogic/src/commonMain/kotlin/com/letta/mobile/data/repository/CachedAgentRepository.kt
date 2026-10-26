package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.AgentIrohSource
import com.letta.mobile.data.repository.api.AgentLocalCache
import com.letta.mobile.data.repository.api.AgentRemoteSource
import kotlin.time.Clock
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("NoDetachedCoroutineLifecycle")
fun defaultCachedAgentRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

// Phase 5a: platform-neutral cached agent repository. Android supplies Room via
// [localCache]; Iroh via [irohAgentSource].
open class CachedAgentRepository(
    private val remote: RemoteRoute<AgentRemoteSource>,
    private val localCache: (() -> AgentLocalCache)? = null,
    private val repositoryScope: CoroutineScope = defaultCachedAgentRepositoryScope(),
    transport: IChannelTransport? = null,
    // letta-mobile-71orq: Iroh admin_rpc agent reads. Platform wires
    // [AgentIrohSource] (Android: IrohAdminRpcAgentSource).
    private val irohAgentSource: AgentIrohSource? = null,
) : IAgentRepository, BackendScopedCache {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshError = MutableStateFlow<Throwable?>(null)
    override val refreshError: StateFlow<Throwable?> = _refreshError.asStateFlow()
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L
    // Entries live only as long as the cached agents or their active requests.
    private val agentFetchedAt = mutableMapOf<AgentId, Long>()
    private val agentRequests = mutableMapOf<AgentId, Deferred<Result<Agent>>>()
    private var refreshRequest: Deferred<Result<Unit>>? = null
    private val changedDuringRefresh = mutableSetOf<AgentId>()
    private var cacheGeneration = 0L
    internal var clock: Clock = Clock.System

    init {
        repositoryScope.launch { loadCachedAgents() }
        transport?.let { channelTransport ->
            repositoryScope.launch { observeAgentUpdated(channelTransport) }
            repositoryScope.launch { observeReconnects(channelTransport) }
        }
    }

    private suspend fun loadCachedAgents() {
        // Resolve + read inside try: Lazy/DAO failures must not escape as
        // uncaught Dispatchers.IO exceptions (tests historically passed a
        // broken Lazy mock; production Room build can also throw).
        try {
            val generation = refreshMutex.withLock { cacheGeneration }
            val cache = localCache?.invoke() ?: return
            val cached = cache.getAllOnce()
            // Do not overwrite a refresh that completed while getAllOnce
            // was suspended. Assigning an empty cache to an empty StateFlow is a no-op.
            refreshMutex.withLock {
                if (generation == cacheGeneration && _agents.value.isEmpty()) {
                    _agents.value = cached
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Telemetry.event("CachedAgentRepository", "Failed to load cached agents", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN)
        }
    }

    override suspend fun countAgents(): Int {
        val irohSource = irohAgentSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.countAgents()
        }
        return remote.source().countAgents()
    }

    /**
     * Slim agent list for picker UIs (Schedules dropdown). Hits the
     * admin-shim's opt-in `GET /v1/agents?slim=true` so the picker pulls a
     * small `{id, name, description}` projection instead of the ~621KB full
     * agents payload. Deliberately SEPARATE from [refreshAgents]/[agents],
     * which full-object consumers (edit-agent, chat config, …) still need.
     */
    override suspend fun listAgentSummaries(): List<AgentSummary> {
        if (irohAgentSource?.shouldUseIroh() == true) {
            return irohAgentSource.listAgents()
                .distinctBy { it.id }
                .map { AgentSummary(id = it.id, name = it.name, description = it.description) }
        }
        return fetchSlimAgentSummaries()
    }

    private suspend fun fetchSlimAgentSummaries(): List<AgentSummary> {
        val merged = mutableListOf<AgentSummary>()
        var offset = 0
        while (true) {
            val page = remote.source().listAgentsSlim(limit = CACHE_REFRESH_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break
            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) break
            merged += newAgents
            if (isFinalPage(page.size)) break
            offset += page.size
        }
        return merged
    }

    override suspend fun refreshAgents() {
        refreshAgents(maxAgeMs = null)
    }

    private suspend fun refreshAgents(maxAgeMs: Long?, generation: Long? = null): Boolean {
        while (true) {
            var started = false
            val request = refreshMutex.withLock {
                if (generation != null && generation != cacheGeneration) return false
                if (maxAgeMs != null && hasFreshAgents(maxAgeMs)) return false
                refreshRequest?.takeUnless { it.isCompleted } ?: startRefreshLocked().also { started = true }
            }
            request.await().getOrThrow()
            if (started) return true
            // A forced refresh starts after the preceding list; stale-only callers recheck its age.
        }
    }

    private fun startRefreshLocked(): Deferred<Result<Unit>> {
        agentRequests.values.forEach { it.cancel() }
        agentRequests.clear()
        agentFetchedAt.clear()
        changedDuringRefresh.clear()
        lateinit var request: Deferred<Result<Unit>>
        request = repositoryScope.async(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                val fresh = fetchAgentsForCache { partial ->
                    refreshMutex.withLock {
                        checkRefreshCurrent(request)
                        publishRosterLocked(partial, completed = false)
                    }
                }
                refreshMutex.withLock {
                    checkRefreshCurrent(request)
                    publishRosterLocked(fresh, completed = true)
                    persistRefreshedAgents(_agents.value)
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                failure = cancelled
                throw cancelled
            } catch (t: Throwable) {
                failure = t
                Result.failure(t)
            } finally {
                withContext(NonCancellable) {
                    refreshMutex.withLock {
                        if (refreshRequest === request) {
                            refreshRequest = null
                            changedDuringRefresh.clear()
                            _isRefreshing.value = false
                            _refreshError.value = failure
                        }
                    }
                }
            }
        }
        refreshRequest = request
        _isRefreshing.value = true
        return request
    }

    private fun checkRefreshCurrent(request: Deferred<Result<Unit>>) {
        request.ensureActive()
        if (refreshRequest !== request) throw CancellationException("Agent cache reset")
    }

    private fun publishRosterLocked(fresh: List<Agent>, completed: Boolean) {
        // A targeted read, push or deletion after this list started wins over its older snapshot.
        val current = _agents.value.filter { it.id in changedDuringRefresh }
        _agents.value = fresh.filterNot { it.id in changedDuringRefresh } + current
        agentFetchedAt.keys.retainAll(_agents.value.map { it.id }.toSet())
        if (completed) {
            val now = nowMillis()
            fresh.filterNot { it.id in changedDuringRefresh }.forEach { agentFetchedAt[it.id] = now }
            lastRefreshAtMillis = now
        }
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            cacheGeneration += 1
            refreshRequest?.cancel()
            refreshRequest = null
            agentRequests.values.forEach { it.cancel() }
            agentRequests.clear()
            agentFetchedAt.clear()
            changedDuringRefresh.clear()
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
            // Persist when a local cache is bound (Room on Android).
            localCache?.invoke()?.deleteAll()
        }
    }

    private suspend fun persistRefreshedAgents(fresh: List<Agent>) {
        try {
            val cache = localCache?.invoke() ?: return
            cache.insertAll(fresh)
            if (fresh.isEmpty()) {
                cache.deleteAll()
            } else {
                cache.deleteExcept(fresh.map { it.id.value })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Telemetry.event("CachedAgentRepository", "Failed to cache agents to Room", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN)
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
        return fetchRemoteAgentsLoop(onProgress)
    }

    private suspend fun fetchRemoteAgentsLoop(
        onProgress: suspend (List<Agent>) -> Unit,
    ): List<Agent> {
        val merged = mutableListOf<Agent>()
        var offset = 0
        while (true) {
            val page = remote.source().listAgents(limit = CACHE_REFRESH_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) {
                Telemetry.event("CachedAgentRepository", "Agent list offset pagination made no progress; falling back to count-sized fetch", level = Telemetry.Level.WARN)
                return fetchAgentsWithoutOffsetFallback(onProgress)
            }

            merged += newAgents
            onProgress(merged.toList())
            if (isFinalPage(page.size)) break
            offset += page.size
        }
        return merged
    }

    private fun isFinalPage(pageSize: Int): Boolean =
        pageSize < CACHE_REFRESH_PAGE_SIZE && pageSize % 20 != 0

    private suspend fun fetchAgentsWithoutOffsetFallback(
        onProgress: suspend (List<Agent>) -> Unit,
    ): List<Agent> {
        val fallbackLimit = runCatchingCancellable { remote.source().countAgents() }
            .getOrDefault(FALLBACK_FULL_FETCH_LIMIT)
            .coerceAtLeast(CACHE_REFRESH_PAGE_SIZE)
        val fullList = remote.source().listAgents(limit = fallbackLimit, offset = null)
            .distinctBy { it.id }
        if (fullList.isNotEmpty()) {
            onProgress(fullList)
        }
        return fullList
    }

    override fun getCachedAgent(id: AgentId): Agent? {
        val cache = _agents.value
        val hit = cache.find { it.id == id }
        if (hit == null) {
            // letta-mobile-z5lqt: telemetry only. A miss previously returned
            // null with no signal at all, so a truncated roster was invisible
            // here. Stays pure and non-suspending: no fetch, no retry, and the
            // null return is unchanged.
            RosterNameTelemetry.cacheMiss(
                agentId = id.value,
                cacheSize = cache.size,
                source = "CachedAgentRepository",
            )
        }
        return hit
    }

    fun hasFreshAgents(maxAgeMs: Long): Boolean {
        return _agents.value.isNotEmpty() && nowMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = refreshAgents(maxAgeMs)

    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        val (cached, fresh) = refreshMutex.withLock {
            _agents.value.find { it.id == id } to isAgentFreshLocked(id)
        }
        if (cached != null) emit(cached)
        if (!fresh) emit(getFreshAgent(id))
    }

    private fun isAgentFreshLocked(id: AgentId): Boolean =
        agentFetchedAt[id]?.let { nowMillis() - it <= SINGLE_AGENT_FRESH_WINDOW_MS } == true

    private suspend fun getFreshAgent(id: AgentId, force: Boolean = false): Agent {
        val request = refreshMutex.withLock {
            if (force) invalidateAgentLocked(id)
            if (isAgentFreshLocked(id)) _agents.value.find { it.id == id }?.let { return it }
            agentRequests[id]?.takeUnless { it.isCompleted } ?: startAgentRequestLocked(id, persist = force)
        }
        return request.await().getOrThrow()
    }

    private fun startAgentRequestLocked(id: AgentId, persist: Boolean): Deferred<Result<Agent>> {
        lateinit var request: Deferred<Result<Agent>>
        request = repositoryScope.async(start = CoroutineStart.LAZY) {
            try {
                runCatchingCancellable {
                    val fresh = fetchAgentRemote(id)
                    refreshMutex.withLock {
                        request.ensureActive()
                        if (agentRequests[id] !== request) throw CancellationException("Agent cache invalidated")
                        updateAgentInCache(fresh)
                        agentFetchedAt[id] = nowMillis()
                        if (refreshRequest != null) changedDuringRefresh += id
                        if (persist) {
                            runCatchingCancellable { localCache?.invoke()?.upsert(fresh) }
                                .onFailure { e -> Telemetry.event("CachedAgentRepository", "agent_updated cache persist failed for ${id.value}", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN) }
                        }
                    }
                    fresh
                }
            } finally {
                withContext(NonCancellable) {
                    refreshMutex.withLock {
                        if (agentRequests[id] === request) agentRequests.remove(id)
                    }
                }
            }
        }
        agentRequests[id] = request
        return request
    }

    private fun invalidateAgentLocked(id: AgentId) {
        agentFetchedAt.remove(id)
        agentRequests.remove(id)?.cancel()
        if (refreshRequest != null) changedDuringRefresh += id
    }

    private suspend fun removeAgentFromCache(id: AgentId, generation: Long? = null) {
        refreshMutex.withLock {
            if (generation != null && generation != cacheGeneration) return
            invalidateAgentLocked(id)
            _agents.update { current -> current.filterNot { it.id == id } }
            localCache?.invoke()?.deleteById(id.value)
        }
    }

    /**
     * Fetch a single agent from the active backend. Routes over the Iroh admin
     * RPC control channel when the backend is iroh://,
     * falling back to the raw HTTP AgentApi otherwise.
     */
    private suspend fun fetchAgentRemote(id: AgentId): Agent =
        if (irohAgentSource?.shouldUseIroh() == true) {
            irohAgentSource.getAgent(id)
        } else {
            remote.source().getAgent(id)
        }

    private suspend fun refreshAgent(agentId: AgentId): Result<Agent> = runCatchingCancellable {
        getFreshAgent(agentId, force = true)
    }

    private suspend fun observeAgentUpdated(channelTransport: IChannelTransport) {
        observeAgentUpdates(
            transport = channelTransport,
            onDeleted = { agentId ->
                runCatchingCancellable { removeAgentFromCache(agentId) }
                    .onFailure { e -> Telemetry.event("CachedAgentRepository", "agent_updated delete cache update failed for ${agentId.value}", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN) }
            },
            onChanged = { agentId ->
                refreshAgent(agentId)
                    .onFailure { e -> Telemetry.event("CachedAgentRepository", "agent_updated refresh failed for ${agentId.value}", "detail" to e.message, level = Telemetry.Level.WARN) }
            },
        )
    }

    private suspend fun observeReconnects(channelTransport: IChannelTransport) {
        // One paged list call instead of a GET /v1/agents/{id} per cached agent:
        // with ~100 cached agents (mostly ephemeral `agent-local-*` subagents) the
        // per-agent loop serialized ~5s of sequential requests on every reconnect
        // (letta-mobile-vcmin).
        observeReconnectRefresh(channelTransport) {
            runCatchingCancellable { refreshAgents() }
                .onFailure { e -> Telemetry.event("CachedAgentRepository", "reconnect agent refresh failed", "detail" to e.message, level = Telemetry.Level.WARN) }
        }
    }

    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview =
        getContextBreakdown(agentId, conversationId, reportedTotal = null)

    override suspend fun getContextBreakdown(
        agentId: AgentId,
        conversationId: ConversationId?,
        reportedTotal: Int?,
    ): ContextWindowOverview {
        val irohSource = irohAgentSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.getContextBreakdown(agentId, conversationId, reportedTotal)
        }
        return remote.source().getContextWindow(agentId, conversationId)
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val irohSource = irohAgentSource
        val agent = if (irohSource != null && irohSource.shouldUseIroh()) {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
                coerceInputValues = true
            }
            val paramsJson = json.encodeToString(AgentCreateParams.serializer(), params)
            irohSource.createAgent(paramsJson)
        } else {
            remote.source().createAgent(params)
        }
        refreshAgents()
        return agent
    }

    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent {
        val generation = refreshMutex.withLock {
            invalidateAgentLocked(id)
            cacheGeneration
        }
        // P4 iroh purity: route updates over admin_rpc when using Iroh.
        val irohSource = irohAgentSource
        val agent = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.updateAgent(
                id,
                kotlinx.serialization.json.Json { encodeDefaults = false; explicitNulls = false }
                    .encodeToString(AgentUpdateParams.serializer(), params),
            )
        } else {
            remote.source().updateAgent(id, params)
        }
        refreshAgents(maxAgeMs = null, generation = generation)
        return agent
    }

    override suspend fun deleteAgent(id: AgentId) {
        val generation = refreshMutex.withLock {
            invalidateAgentLocked(id)
            cacheGeneration
        }
        val irohSource = irohAgentSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.deleteAgent(id)
        } else {
            remote.source().deleteAgent(id)
        }
        try {
            removeAgentFromCache(id, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Telemetry.event("CachedAgentRepository", "Failed to update cached agents after delete", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN)
        }
    }

    override suspend fun exportAgent(id: AgentId): String {
        return remote.source().exportAgent(id)
    }

    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse {
        val response = remote.source().importAgent(params)
        refreshAgents()
        return response
    }

    override suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        remote.source().attachArchive(agentId, archiveId)
        refreshAgents()
    }

    override suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        remote.source().detachArchive(agentId, archiveId)
        refreshAgents()
    }

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

    override suspend fun checkpointAndRestoreConfig(
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
                Telemetry.event("CachedAgentRepository", "Agent config drift detected after operation, restoring checkpoint", level = Telemetry.Level.WARN)
                Telemetry.event(
                    "CachedAgentRepository",
                    "configDriftDetected",
                    "agentId" to agentId,
                    "originalModel" to checkpoint.model,
                    "driftedModel" to updatedAgent.model,
                    level = Telemetry.Level.WARN
                )
                updateAgent(agentId, checkpoint.toUpdateParams())
                Telemetry.event(
                    "CachedAgentRepository",
                    "configRestored",
                    "agentId" to agentId,
                    "restoredModel" to checkpoint.model
                )
            }
        }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private companion object {
        const val CACHE_REFRESH_PAGE_SIZE = 50
        const val FALLBACK_FULL_FETCH_LIMIT = 5_000

        /**
         * A successful bulk or individual read makes an agent fresh for this
         * window. [getAgent] emits cached data first and only fetches when stale.
         * 30 s covers a chat-open + reply cycle without
         * risking a stale read for users who keep a chat open across a long
         * idle period.
         */
        const val SINGLE_AGENT_FRESH_WINDOW_MS = 30_000L
    }

    /**
     * Test seam: rewinds completed bulk and per-agent freshness together
     * without sleeping the test dispatcher.
     */
    fun setLastRefreshForTest(epochMs: Long) {
        lastRefreshAtMillis = epochMs
        agentFetchedAt.keys.toList().forEach { agentFetchedAt[it] = epochMs }
    }
}
