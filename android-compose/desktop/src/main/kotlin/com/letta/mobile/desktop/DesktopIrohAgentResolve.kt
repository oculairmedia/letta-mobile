package com.letta.mobile.desktop

import com.letta.mobile.util.attr
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
        "agentId" attr id,
        "errorClass" attr (t::class.simpleName ?: "Unknown"),
    )
    null
}

/**
 * Distinguishes "resolver threw" from "resolver ran and found nothing" — both
 * previously presented identically (raw ids in the UI, silence in the log),
 * which is why this went undiagnosed.
 */
private fun reportUnresolved(agentIds: Set<String>, resolvedIds: Set<String>) {
    val missing = agentIds.count { it !in resolvedIds }
    if (missing == 0) return
    Telemetry.event(
        "DesktopAgents", "resolve.unresolvedIds",
        "requestedIds" attr agentIds.size,
        "unresolved" attr missing,
    )
}

/** [agentIds]' agents: the cached roster's, else each fetched on its own (a dead one skipped, not fatal). */
internal suspend fun resolveDesktopAgents(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
): Map<String, Agent> {
    val resolved = mutableMapOf<String, Agent>()
    agentRepository.agents.value.forEach { agent -> resolved[agent.id.value] = agent }
    agentIds.filter { it !in resolved }.forEach { id ->
        lookupAgent(id, agentRepository)?.let { resolved[id] = it }
    }
    reportUnresolved(agentIds, resolved.keys)
    return resolved
}
