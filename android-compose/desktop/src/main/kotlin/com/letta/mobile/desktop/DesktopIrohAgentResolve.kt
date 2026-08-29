package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private suspend fun <T : Any> resolveDesktopAgentField(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
    refreshBeforeResolve: Boolean,
    fromAgent: (Agent) -> T?,
): Map<String, T> {
    // A refresh failure (backend unreachable, RPC error) must not discard the
    // names we can still resolve from cache. Before this was guarded, one
    // throw here aborted the whole resolution and every conversation fell all
    // the way through to its raw `agent-<uuid>` fallback in
    // toChatConversationSummary -- which is what the user sees in the tab strip.
    if (refreshBeforeResolve) {
        try {
            agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Telemetry.error("DesktopAgents", "resolve.refreshFailed", t, "requestedIds" to agentIds.size)
        }
    }
    val resolved = mutableMapOf<String, T>()
    agentRepository.agents.value.forEach { agent ->
        fromAgent(agent)?.let { resolved[agent.id.value] = it }
    }
    // Per-id isolation. A conversation can outlive its agent (deleted, or a
    // stale `agent-local-*` from an earlier backend), and the repository
    // signals that by THROWING -- getAgent(id).first() raises
    // NoSuchElementException rather than returning null. Unguarded, one dead
    // agent aborted the whole loop and discarded every name resolved before
    // it, so a single stale id blanked the entire tab strip and sidebar down
    // to raw `agent-<uuid>` labels.
    agentIds.filter { it !in resolved }.forEach { id ->
        try {
            val value = agentRepository.getCachedAgent(id)?.let(fromAgent)
                ?: agentRepository.getAgent(id).first()?.let(fromAgent)
            value?.let { resolved[id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Telemetry.event(
                "DesktopAgents", "resolve.agentLookupFailed",
                "agentId" to id,
                "errorClass" to (t::class.simpleName ?: "Unknown"),
            )
        }
    }
    // Distinguishes "resolver threw" from "resolver ran and found nothing":
    // both previously presented identically (raw ids in the UI, silence in the
    // log), which is why this went undiagnosed.
    val missing = agentIds.count { it !in resolved }
    if (missing > 0) {
        Telemetry.event(
            "DesktopAgents", "resolve.unresolvedIds",
            "requestedIds" to agentIds.size,
            "unresolved" to missing,
            "refreshed" to refreshBeforeResolve,
        )
    }
    return resolved
}

private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }

internal suspend fun resolveDesktopAgentNames(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
): Map<String, String> = resolveDesktopAgentField(
    agentIds = agentIds,
    agentRepository = agentRepository,
    refreshBeforeResolve = true,
    fromAgent = { agent -> nonBlank(agent.name) },
)

internal suspend fun resolveDesktopAgents(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
): Map<String, Agent> = resolveDesktopAgentField(
    agentIds = agentIds,
    agentRepository = agentRepository,
    refreshBeforeResolve = false,
    fromAgent = { it },
)
