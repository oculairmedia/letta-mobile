package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.flow.first

private suspend fun <T : Any> resolveDesktopAgentField(
    agentIds: Set<String>,
    agentRepository: IAgentRepository,
    refreshBeforeResolve: Boolean,
    fromAgent: (Agent) -> T?,
): Map<String, T> {
    if (refreshBeforeResolve) agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
    val resolved = mutableMapOf<String, T>()
    agentRepository.agents.value.forEach { agent ->
        fromAgent(agent)?.let { resolved[agent.id.value] = it }
    }
    agentIds.filter { it !in resolved }.forEach { id ->
        val value = agentRepository.getCachedAgent(id)?.let(fromAgent)
            ?: agentRepository.getAgent(id).first()?.let(fromAgent)
        value?.let { resolved[id] = it }
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
