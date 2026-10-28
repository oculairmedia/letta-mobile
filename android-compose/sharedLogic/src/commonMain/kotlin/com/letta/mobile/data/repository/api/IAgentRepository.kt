package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IAgentRepository {
    val agents: StateFlow<List<Agent>>
    /**
     * Set to true while [refreshAgents] is in flight (i.e. while the
     * agent cache may not reflect the active backend). Consumers that
     * need to gate "is this id an orphan?" decisions on a known-fresh
     * cache should observe this and only treat a missing id as an
     * orphan once it flips back to false. Particularly important on
     * backend switches, where the cache transitions from one server's
     * agents to another.
     */
    val isRefreshing: StateFlow<Boolean>
    /**
     * Exposes the last encountered refresh/sync exception (e.g. connection timeout,
     * invalid credentials, offline state) so that UI consumers can surface recoverable
     * errors. Set to null when a refresh succeeds.
     */
    val refreshError: StateFlow<Throwable?>
    suspend fun countAgents(): Int
    suspend fun refreshAgents()

    /**
     * Lightweight agent list for picker UIs (e.g. the Schedules dropdown).
     *
     * Returns only the minimal `{id, name, description}` projection a picker
     * needs — selected by id, rendered by name — WITHOUT pulling the full
     * ~621KB agents payload that [refreshAgents]/[agents] carries for
     * full-object consumers. Concrete remote repositories override this to hit
     * the admin-shim's opt-in `GET /v1/agents?slim=true` path.
     *
     * The default implementation derives summaries from the shared full
     * [agents] cache (refreshing it first if empty). This keeps test doubles
     * and non-remote implementations correct without each having to wire a
     * slim endpoint; only the wire-size win requires the override.
     */
    suspend fun listAgentSummaries(): List<AgentSummary> {
        if (agents.value.isEmpty()) {
            refreshAgents()
        }
        return agents.value.map { AgentSummary(id = it.id, name = it.name, description = it.description) }
    }
    suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean
    fun getCachedAgent(id: AgentId): Agent?
    fun getCachedAgent(id: String): Agent? = getCachedAgent(AgentId(id))
    /**
     * One authoritative detail read, independent of the lifetime of [getAgent]'s observation flow.
     * Never satisfies an edit/checkpoint from a list row or hides a fetch failure behind cached data.
     * [force] bypasses the detail TTL; concurrent callers may share an in-flight detail request.
     * Cancellation stops waiting without cancelling a request shared with another caller.
     * Callers own their deadline (the editor bounds this operation to 20 seconds).
     */
    suspend fun getFreshAgent(id: AgentId, force: Boolean = false): Agent

    fun getAgent(id: AgentId): Flow<Agent>
    fun getAgent(id: String): Flow<Agent> = getAgent(AgentId(id))
    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId? = null): ContextWindowOverview
    suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview =
        getContextWindow(AgentId(agentId), conversationId?.let(::ConversationId))
    /**
     * The context breakdown for the drawer meter. [reportedTotal] is the
     * client's latest streamed `context_tokens` (null when it has none or knows it is stale after a
     * compaction); a local-backend host matches its estimated sections to it. Defaults to
     * [getContextWindow] for backends with nothing to match.
     */
    suspend fun getContextBreakdown(
        agentId: AgentId,
        conversationId: ConversationId?,
        reportedTotal: Int?,
    ): ContextWindowOverview = getContextWindow(agentId, conversationId)
    suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit)
    suspend fun checkpointAndRestoreConfig(agentId: String, operation: suspend () -> Unit) =
        checkpointAndRestoreConfig(AgentId(agentId), operation)
    /**
     * False when this backend cannot serve [createAgent] (the bundled local
     * App Server and an Iroh agent directory have no create route), so pages hide the action instead
     * of offering one that always fails.
     */
    val supportsCreateAgent: Boolean get() = true
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent
    suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent = updateAgent(AgentId(id), params)
    suspend fun deleteAgent(id: AgentId)
    suspend fun deleteAgent(id: String) = deleteAgent(AgentId(id))
    suspend fun exportAgent(id: AgentId): String
    suspend fun exportAgent(id: String): String = exportAgent(AgentId(id))
    suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse
    suspend fun attachArchive(agentId: AgentId, archiveId: String)
    suspend fun attachArchive(agentId: String, archiveId: String) = attachArchive(AgentId(agentId), archiveId)
    suspend fun detachArchive(agentId: AgentId, archiveId: String)
    suspend fun detachArchive(agentId: String, archiveId: String) = detachArchive(AgentId(agentId), archiveId)
}

/** How old a cached roster a conversation list still reads by. */
const val LIST_ROSTER_MAX_AGE_MS: Long = 60_000L

/**
 * The agents a conversation list reads by: refreshed when older than [maxAgeMs]. A failed refresh
 * keeps the cached agents, so a blip does not empty every list; with none cached it fails.
 */
suspend fun IAgentRepository.rosterForLists(maxAgeMs: Long = LIST_ROSTER_MAX_AGE_MS): List<Agent> {
    runCatching { refreshAgentsIfStale(maxAgeMs) }.exceptionOrNull()?.let { failure ->
        if (failure is CancellationException || agents.value.isEmpty()) throw failure
    }
    return agents.value
}
