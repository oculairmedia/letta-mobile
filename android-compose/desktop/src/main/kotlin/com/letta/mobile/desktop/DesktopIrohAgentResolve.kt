package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Refreshes the agent cache, downgrading any failure to telemetry.
 *
 * A refresh failure (backend unreachable, RPC error) must not discard the
 * names still resolvable from cache: an unguarded throw here aborted the whole
 * resolution and dropped every conversation label to its raw `agent-<uuid>`
 * fallback in `toChatConversationSummary`.
 */
private suspend fun refreshAgentsOrLog(agentRepository: IAgentRepository, requestedIds: Int) {
    try {
        agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Telemetry.error("DesktopAgents", "resolve.refreshFailed", t, "requestedIds" to requestedIds)
    }
}

/**
 * Resolves one agent's field, or null when that agent cannot be fetched.
 *
 * A conversation can outlive its agent (deleted, or a stale `agent-local-*`
 * from an earlier backend) and the repository signals that by THROWING —
 * `getAgent(id).first()` raises NoSuchElementException rather than returning
 * null. Isolating it per id is what stops one dead agent from discarding every
 * name resolved before it.
 */
private suspend fun lookupAgent(
    id: String,
    agentRepository: IAgentRepository,
): Agent? = try {
    agentRepository.getCachedAgent(id) ?: agentRepository.getAgent(id).first()
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Telemetry.event(
        "DesktopAgents", "resolve.agentLookupFailed",
        "agentId" to id,
        "errorClass" to (t::class.simpleName ?: "Unknown"),
    )
    null
}

/**
 * Distinguishes "resolver threw" from "resolver ran and found nothing" — both
 * previously presented identically (raw ids in the UI, silence in the log),
 * which is why this went undiagnosed.
 */
private fun reportUnresolved(agentIds: Set<String>, resolvedIds: Set<String>, refreshed: Boolean) {
    val missing = agentIds.count { it !in resolvedIds }
    if (missing == 0) return
    Telemetry.event(
        "DesktopAgents", "resolve.unresolvedIds",
        "requestedIds" to agentIds.size,
        "unresolved" to missing,
        "refreshed" to refreshed,
    )
}

private suspend fun resolveDesktopAgentMap(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
    refreshBeforeResolve: Boolean,
): Map<String, Agent> {
    if (refreshBeforeResolve) refreshAgentsOrLog(agentRepository, agentIds.size)
    val resolved = mutableMapOf<String, Agent>()
    agentRepository.agents.value.forEach { agent -> resolved[agent.id.value] = agent }
    agentIds.filter { it !in resolved }.forEach { id ->
        lookupAgent(id, agentRepository)?.let { resolved[id] = it }
    }
    reportUnresolved(agentIds, resolved.keys, refreshBeforeResolve)
    return resolved
}

internal suspend fun resolveDesktopAgentNames(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
): Map<String, String> =
    resolveDesktopAgentMap(agentIds, agentRepository, refreshBeforeResolve = true)
        .mapNotNull { (id, agent) -> agent.name.takeIf { it.isNotBlank() }?.let { id to it } }
        .toMap()

internal suspend fun resolveDesktopAgents(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
): Map<String, Agent> =
    resolveDesktopAgentMap(agentIds, agentRepository, refreshBeforeResolve = false)
