package com.letta.mobile.data.agents

/** A rail entry: one or more agents that share a display name, stacked together. */
data class AgentRailGroup(
    val name: String,
    val agentIds: List<String>,
)

/** An Element-style space: a named cluster of rail groups with aggregate impact. */
data class AgentRailSpace(
    val name: String,
    val groups: List<AgentRailGroup>,
) {
    val agentCount: Int get() = groups.sumOf { it.agentIds.size }
}

const val RAIL_CATCH_ALL_SPACE = "Agents"

/**
 * Derives spaces from naming conventions ("PM - social-hause" → space "PM"):
 * a prefix before " - " shared by at least two groups becomes a space;
 * everything else lands in the catch-all [RAIL_CATCH_ALL_SPACE]. Order
 * follows first appearance so recency is preserved within and across spaces.
 */
fun deriveAgentSpaces(groups: List<AgentRailGroup>): List<AgentRailSpace> {
    val prefixOf = { group: AgentRailGroup ->
        group.name.substringBefore(" - ", missingDelimiterValue = "").takeIf { it.isNotBlank() }
    }
    val prefixCounts = groups.mapNotNull(prefixOf).groupingBy { it }.eachCount()
    val spaced = LinkedHashMap<String, MutableList<AgentRailGroup>>()
    groups.forEach { group ->
        val prefix = prefixOf(group)?.takeIf { (prefixCounts[it] ?: 0) >= 2 }
        spaced.getOrPut(prefix ?: RAIL_CATCH_ALL_SPACE) { mutableListOf() }.add(group)
    }
    return spaced.map { (name, members) -> AgentRailSpace(name = name, groups = members) }
}
