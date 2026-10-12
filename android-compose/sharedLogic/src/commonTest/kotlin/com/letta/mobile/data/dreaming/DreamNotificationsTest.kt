package com.letta.mobile.data.dreaming

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-iczm7: when a dream finished, and the per-agent opt-in. */
class DreamNotificationsTest {
    private fun entry(id: String, type: String, status: String, parent: String? = "agent-1") =
        SubagentEntry(toolCallId = id, description = "Reflect on recent conversations", subagentType = type, status = status, parentAgentId = parent)

    @Test
    fun aDreamFinishesOnlyOnAnExplicitTerminalStatus() {
        val tracker = DreamFinishTracker()
        assertTrue(tracker.update(listOf(entry("t1", "Reflection", "running"))).isEmpty())
        // Dropped from a snapshot: maybe still running, never announced.
        assertTrue(tracker.update(emptyList()).isEmpty())
        val finished = tracker.update(listOf(entry("t1", "Reflection", "completed")))
        assertEquals(listOf(DreamFinished("agent-1", "Reflect on recent conversations", succeeded = true)), finished)
        // Announced once.
        assertTrue(tracker.update(listOf(entry("t1", "Reflection", "completed"))).isEmpty())
    }

    @Test
    fun failuresAreReportedAndUserSubagentsAndMemoryUpkeepAreNot() {
        val tracker = DreamFinishTracker()
        tracker.update(listOf(entry("t1", "Reflection", "running"), entry("t2", "General-purpose", "running"), entry("t3", "Memory", "running")))
        val finished = tracker.update(listOf(entry("t1", "Reflection", "failed"), entry("t2", "General-purpose", "completed"), entry("t3", "Memory", "completed")))
        assertEquals(listOf(false), finished.map { it.succeeded })
    }

    @Test
    fun aDreamLabelledRunCountsLikeReflection() {
        // SubagentKind.Dream (ulcg6 roster) is the same dream as Reflection; one classifier decides.
        val tracker = DreamFinishTracker()
        tracker.update(listOf(entry("t1", "Dream", "running")))
        assertEquals(listOf(true), tracker.update(listOf(entry("t1", "Dream", "completed"))).map { it.succeeded })
    }

    @Test
    fun notificationsAreOptInPerAgent() {
        val prefs = DreamNotifyPrefs(MapStore())
        assertFalse(prefs.isEnabled("agent-1"))
        prefs.setEnabled("agent-1", true)
        assertTrue(prefs.isEnabled("agent-1"))
        assertFalse(prefs.isEnabled("agent-2"))
        prefs.setEnabled("agent-1", false)
        assertFalse(prefs.isEnabled("agent-1"))
    }

    private class MapStore : SecureSettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }
}
