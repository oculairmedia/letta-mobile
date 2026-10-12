package com.letta.mobile.data.dreaming

import com.letta.mobile.data.memory.memfs.MemfsCommit
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-iczm7: settings, letta-code's run answers and dream commits, from letta-code's real shapes. */
class DreamModelsTest {
    @Test
    fun settingsRoundTripTheWireFields() {
        val settings = DreamSettings(DreamTrigger.StepCount, 12, DreamMerge.Explicit, "only durable facts")
        assertEquals(settings, DreamSettings.fromWire(settings.toWire()))
    }

    @Test
    fun missingFieldsTakeLettaCodesDefaultsAndAnUnknownTriggerIsNoSettings() {
        val sparse = DreamSettings.fromWire(buildJsonObject { put("trigger", "compaction-event") })
        assertEquals(DreamSettings.DEFAULT, sparse)
        assertNull(DreamSettings.fromWire(buildJsonObject { put("trigger", "on-idle") }))
        assertNull(DreamSettings.fromWire(null))
    }

    @Test
    fun underscoreTriggersFromOlderHostsAreRead() {
        assertEquals(DreamTrigger.StepCount, DreamTrigger.fromWire("step_count"))
        assertEquals(DreamTrigger.CompactionEvent, DreamTrigger.fromWire("COMPACTION_EVENT"))
    }

    @Test
    fun offStillSendsAPositiveStepCountBecauseLettaCodeRequiresIt() {
        val wire = DreamSettings(DreamTrigger.Off, stepCount = 0).toWire()
        assertEquals("off", wire.getValue("trigger").jsonPrimitive.content)
        assertEquals(1, wire.getValue("step_count").jsonPrimitive.int)
        assertFalse(DreamSettings(DreamTrigger.Off).automatic)
        assertTrue(DreamSettings.DEFAULT.automatic)
    }

    @Test
    fun runAnswersAreReadFromHandleReflectCommandsText() {
        fun status(output: String, success: Boolean = true) = DreamRunStart.parse(success, output).status
        assertEquals(DreamStartStatus.Started, status("Started a reflection pass for this conversation."))
        assertEquals(DreamStartStatus.AlreadyRunning, status("A reflection agent is already running for this conversation."))
        assertEquals(DreamStartStatus.NothingNew, status("No new transcript content to reflect on for this conversation."))
        assertEquals(DreamStartStatus.MemoryOff, status("Reflection needs the memory filesystem to be enabled for this agent."))
        assertEquals(DreamStartStatus.Failed, status("Failed to start reflection: boom"))
        assertEquals(DreamStartStatus.Failed, status("", success = false))
    }

    @Test
    fun dreamCommitsAreTheReflectionMarkedOnesWithTheirMarkersStripped() {
        val commits = listOf(
            commit("a1", "merge(reflection): Consolidated tooling preferences"),
            commit("b2", "chore: update persona"),
            commit("c3", "feat(reflection): Learned the user's build commands 🔮\n\nReviewed transcript: ..."),
            commit("d4", "fix: reflection: drop stale project notes"),
        )
        val runs = DreamCommits.runs(commits)
        assertEquals(listOf("a1", "c3", "d4"), runs.map { it.sha })
        assertEquals(DreamCommitKind.Merge, runs[0].kind)
        assertEquals("Consolidated tooling preferences", runs[0].summary)
        assertEquals(DreamCommitKind.Change, runs[1].kind)
        assertEquals("Learned the user's build commands", runs[1].summary)
        assertEquals("fix: reflection: drop stale project notes", runs[2].summary)
    }

    @Test
    fun liveActivityIsOnlyRunningBackgroundMemoryWork() {
        val entries = listOf(
            entry("t1", "Reflection", "running"),
            entry("t2", "Memory", "running"),
            entry("t3", "General-purpose", "running"),
            entry("t4", "Reflection", "completed"),
            entry("t5", "reflection integration", "running"),
        )
        val live = DreamActivity.of(entries)
        assertEquals(listOf("t1", "t2", "t5"), live.map { it.id })
        assertEquals(SubagentKind.Memory, live[1].kind)
    }

    private fun commit(sha: String, message: String) = MemfsCommit(sha, message, "2026-10-11T10:00:00Z", "Reflection Subagent")

    private fun entry(id: String, type: String, status: String) =
        SubagentEntry(toolCallId = id, description = "d-$id", subagentType = type, status = status, startedAt = "2026-10-11T10:00:00Z")
}
