package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lock the stack-grouping fallback behavior addressed by PR #759 review:
 * same-name stacks must surface the freshest member as the click fallback,
 * not the first-seen one.
 */
class GroupRailAgentsByNameTest {

    @Test
    fun `stack with parsed timestamps picks newest member as fallback`() {
        val agents = listOf(
            Triple("agent-old", "Letta Code", "2026-04-19T06:00:00Z"),
            Triple("agent-newer", "Letta Code", "2026-04-19T10:00:00Z"),
            Triple("agent-newest", "Letta Code", "2026-04-19T14:30:00Z"),
        )
        val groups = groupRailAgentsByName(agents)
        assertEquals(1, groups.size)
        val stack = groups.single()
        assertEquals("Letta Code", stack.name)
        assertEquals(listOf("agent-newest", "agent-newer", "agent-old"), stack.agentIds)
    }

    @Test
    fun `unparseable timestamps sort LAST so fresh activity wins`() {
        val agents = listOf(
            Triple("agent-undated-a", "Letta Code", "Remote"),
            Triple("agent-undated-b", "Letta Code", "Queued"),
            Triple("agent-fresh", "Letta Code", "2026-04-19T14:00:00Z"),
            Triple("agent-older", "Letta Code", "2026-04-19T08:00:00Z"),
        )
        val groups = groupRailAgentsByName(agents)
        val stack = groups.single()
        // The two parseable timestamps sort newest-first; the two unparseable
        // entries sort LAST (insertion order preserved by thenByDescending id).
        assertEquals(
            listOf("agent-fresh", "agent-older", "agent-undated-b", "agent-undated-a"),
            stack.agentIds,
        )
    }

    @Test
    fun `distinct agents stack separately`() {
        val agents = listOf(
            Triple("agent-a-1", "Letta Code", "2026-04-19T10:00:00Z"),
            Triple("agent-a-2", "Letta Code", "2026-04-19T12:00:00Z"),
            Triple("agent-b-1", "Meridian", "2026-04-19T11:00:00Z"),
        )
        val groups = groupRailAgentsByName(agents)
        assertEquals(2, groups.size)
        val byName = groups.associateBy { it.name }
        assertEquals(listOf("agent-a-2", "agent-a-1"), byName["Letta Code"]?.agentIds)
        assertEquals(listOf("agent-b-1"), byName["Meridian"]?.agentIds)
    }
}