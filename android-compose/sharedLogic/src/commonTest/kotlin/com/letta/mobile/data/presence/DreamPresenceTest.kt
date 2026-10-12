package com.letta.mobile.data.presence

import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-iczm7: who is dreaming, and that a dream never changes the turn. */
class DreamPresenceTest {
    private fun entry(id: String, type: String, status: String, parent: String? = "agent-1") =
        SubagentEntry(toolCallId = id, description = "Reflect on recent conversations", subagentType = type, status = status, parentAgentId = parent)

    @Test
    fun dreamingAgentsAreTheParentsOfRunningBackgroundRuns() {
        val entries = listOf(
            entry("t1", "Reflection", "running"),
            entry("t2", "Memory", "running", parent = "agent-2"),
            entry("t3", "General-purpose", "running", parent = "agent-3"),
            entry("t4", "Reflection", "completed", parent = "agent-4"),
            entry("t5", "Reflection", "running", parent = null),
        )
        assertEquals(setOf("agent-1", "agent-2"), dreamingAgentIds(entries))
    }

    @Test
    fun dreamingMarksPresenceWithoutChangingTheTurnAndOnlyAnIdleAgentDozes() {
        val presence = mapOf("agent-1" to AgentPresence(activity = AgentActivityKind.THINKING))
        val merged = presence.withDreaming(setOf("agent-1", "agent-2"))
        assertEquals(AgentActivityKind.THINKING, merged.getValue("agent-1").activity)
        assertTrue(merged.getValue("agent-1").dreaming)
        assertFalse(merged.getValue("agent-1").dozing)
        assertTrue(merged.getValue("agent-2").dozing)
        assertFalse(AgentPresence(dreaming = true, userTyping = true).dozing)
        assertEquals(presence, presence.withDreaming(emptySet()))
    }
}
