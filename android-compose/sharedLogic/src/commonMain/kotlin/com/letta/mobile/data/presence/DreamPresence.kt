package com.letta.mobile.data.presence

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.isBackgroundSubagent

/**
 * letta-mobile-iczm7: the agents with a dream (reflection) or memory-upkeep run in flight, from
 * subagent registry entries (the parent agent of each running background entry).
 */
fun dreamingAgentIds(entries: List<SubagentEntry>): Set<String> = entries
    .filter { it.status == SubagentStatus.RUNNING && it.isBackgroundSubagent() }
    .mapNotNullTo(mutableSetOf()) { it.parentAgentId?.takeIf(String::isNotBlank) }

/** Marks [dreaming] agents in this presence map; an agent with no turn state reads as idle and dreaming. */
fun Map<String, AgentPresence>.withDreaming(dreaming: Set<String>): Map<String, AgentPresence> {
    if (dreaming.isEmpty()) return this
    val merged = toMutableMap()
    dreaming.forEach { agentId -> merged[agentId] = (merged[agentId] ?: AgentPresence.IDLE).copy(dreaming = true) }
    return merged
}
