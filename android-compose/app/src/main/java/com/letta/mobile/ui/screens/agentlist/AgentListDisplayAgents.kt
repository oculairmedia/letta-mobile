package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId

data class AgentListDisplayAgents(
    val visibleFavoriteAgent: Agent?,
    val listAgents: List<Agent>,
)

fun resolveAgentListDisplayAgents(
    filteredAgents: List<Agent>,
    favoriteAgent: Agent?,
    pinnedAgentIds: Set<AgentId> = emptySet(),
): AgentListDisplayAgents {
    val filteredAgentIds = filteredAgents.mapTo(mutableSetOf()) { it.id }
    val visibleFavoriteAgent = favoriteAgent?.takeIf { it.id in filteredAgentIds }
    return AgentListDisplayAgents(
        visibleFavoriteAgent = visibleFavoriteAgent,
        listAgents = filteredAgents
            .filter { it.id != visibleFavoriteAgent?.id }
            .mapIndexed { index, agent -> index to agent }
            .sortedWith(
                compareByDescending<Pair<Int, Agent>> { it.second.id in pinnedAgentIds }
                    .thenBy { it.first },
            )
            .map { it.second },
    )
}

fun shouldShowEmptyAgentCreateAction(
    isShareMode: Boolean,
    isHydrating: Boolean,
    searchQuery: String,
): Boolean = !isShareMode && !isHydrating && searchQuery.isBlank()
