package com.letta.mobile.ui.shell.rail

import com.letta.mobile.data.agents.AgentRailGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShellRailMappingTest {

    @Test
    fun sameNamedAgentsStackAndTheSelectedAgentLeavesTheRail() {
        val groups = ShellRailMapping.groups(
            agents = listOf("a1" to "Alpha", "lc1" to "Letta Code", "lc2" to "Letta Code", "sel" to "Selected"),
            selectedAgentId = "sel",
        )
        assertEquals(
            listOf(AgentRailGroup("Alpha", listOf("a1")), AgentRailGroup("Letta Code", listOf("lc1", "lc2"))),
            groups,
        )
    }

    @Test
    fun placeholderNamesNeverStack() {
        val groups = ShellRailMapping.groups(listOf("agent-1" to "Agent agent-1", "agent-2" to "Agent agent-1"), selectedAgentId = null)
        assertEquals(2, groups.size)
    }

    @Test
    fun anEntryResolvesItsTargetLookAndActivity() {
        val group = AgentRailGroup("Letta Code", listOf("lc1", "lc2"))
        val focus = ShellRailFocus(
            selectedAgentId = "lc2",
            thinkingAgentId = "lc1",
            avatarStyleByAgentId = mapOf("lc2" to 5),
            activityByAgentId = mapOf(
                "lc1" to ShellRailActivity("2026-10-07T10:00:00Z", "older"),
                "lc2" to ShellRailActivity("2026-10-07T11:00:00Z", "newer"),
            ),
        )
        val entry = ShellRailMapping.entry(group, index = 3, focus = focus)
        assertEquals("lc2", entry.agentId)
        assertEquals(5, entry.orbStyle)
        assertTrue(entry.selected)
        assertTrue(entry.thinking)
        assertEquals("newer", entry.activity?.preview)
        assertEquals("Letta Code · 2 agents · thinking…", entry.tooltip)
        assertEquals("L", entry.initial)
    }

    @Test
    fun anUnstyledEntryTakesItsPositionAsItsColour() {
        val entry = ShellRailMapping.entry(AgentRailGroup("Beta", listOf("b1")), index = 4, focus = ShellRailFocus())
        assertEquals("b1", entry.agentId)
        assertEquals(4, entry.orbStyle)
        assertNull(entry.activity)
    }

    @Test
    fun queuedSortsNewestAndUnparseableOldest() {
        assertEquals(Instant.DISTANT_FUTURE, ShellRailMapping.recency("Queued"))
        assertEquals(Instant.DISTANT_PAST, ShellRailMapping.recency("Remote"))
        assertEquals(Instant.parse("2026-10-07T11:00:00Z"), ShellRailMapping.recency("2026-10-07T11:00:00Z"))
    }
}
