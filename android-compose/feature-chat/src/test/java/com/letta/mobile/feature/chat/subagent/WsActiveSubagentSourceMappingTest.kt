package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.model.SubagentTodoProgressWire
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource.Companion.activeOnly
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagent
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagentStatus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pure wire→UI mapping coverage for [WsActiveSubagentSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class WsActiveSubagentSourceMappingTest {

    @Test
    fun `status strings map to ActiveSubagent Status`() {
        assertEquals(ActiveSubagent.Status.RUNNING, SubagentStatus.RUNNING.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.COMPLETED, SubagentStatus.COMPLETED.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.FAILED, SubagentStatus.FAILED.toActiveSubagentStatus())
        // letta-mobile-drv4a: `cancelled` (killed / evicted / orphaned /
        // TaskStop'd / process gone) is terminal → maps to FAILED so the chip
        // lingers then dismisses instead of being stuck running.
        assertEquals(ActiveSubagent.Status.FAILED, SubagentStatus.CANCELLED.toActiveSubagentStatus())
        assertTrue(SubagentStatus.CANCELLED.toActiveSubagentStatus().isTerminal)
        // Forward-compat: unknown status keeps the chip running.
        assertEquals(ActiveSubagent.Status.RUNNING, "some_future_state".toActiveSubagentStatus())
    }


    @Test
    fun `id prefers toolCallId then falls back to taskId`() {
        assertEquals("toolu_1", entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))).toActiveSubagent().id)
        assertEquals("task_2", entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_2"))).toActiveSubagent().id)
    }


    @Test
    fun `entry kind is inferred from correlation keys`() {
        // letta-mobile-pvrrm: tool_call_id present -> a dispatched subagent.
        assertEquals(
            ActiveSubagent.Kind.SUBAGENT,
            entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))).toActiveSubagent().kind,
        )
        // Only a task_id (no tool_call_id) -> a background tool task.
        assertEquals(
            ActiveSubagent.Kind.BACKGROUND_TASK,
            entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_2"))).toActiveSubagent().kind,
        )
    }


    @Test
    fun `startedAt seeds the lastUpdateAt baseline for the stuck heuristic`() {
        // letta-mobile-dvobc: a parseable ISO timestamp becomes the baseline.
        val withTs = SubagentEntry(
            toolCallId = "toolu_1",
            status = SubagentStatus.RUNNING,
            startedAt = "2026-06-05T00:00:00Z",
        ).toActiveSubagent()
        assertEquals(
            java.time.Instant.parse("2026-06-05T00:00:00Z").toEpochMilli(),
            withTs.lastUpdateAt,
        )
        // A malformed timestamp must not crash — it degrades to null.
        val bad = SubagentEntry(
            toolCallId = "toolu_2",
            status = SubagentStatus.RUNNING,
            startedAt = "not-a-date",
        ).toActiveSubagent()
        assertNull(bad.lastUpdateAt)
    }


    @Test
    fun `subagentAgentId is carried through for view-conversation`() {
        // letta-mobile-vo9y1: present -> canViewConversation true.
        val mapped = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()
        assertEquals("agent-local-abc", mapped.subagentAgentId)
        assertTrue(mapped.canViewConversation)

        // Absent / blank -> no affordance.
        assertNull(entry(EntryRefs(toolCallId = ToolCallRef("toolu_2"))).toActiveSubagent().subagentAgentId)
        assertFalse(entry(EntryRefs(toolCallId = ToolCallRef("toolu_3"), subagentAgentId = AgentRef(""))).toActiveSubagent().canViewConversation)
    }


    @Test
    fun `todo_progress is mapped from wire to UI progress model`() {
        val wire = SubagentEntry(
            toolCallId = "toolu_1",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 3, total = 7),
        )
        val mapped = wire.toActiveSubagent()

        assertNotNull(mapped.progress)
        assertEquals(3, mapped.progress?.completed)
        assertEquals(7, mapped.progress?.total)
        assertEquals(3f / 7f, mapped.ringFraction)
        assertTrue(mapped.hasDeterminateProgress)
    }


    @Test
    fun `null todo_progress maps to null progress on ActiveSubagent`() {
        val wire = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")))
        // entry() helper doesn't set todoProgress, so it defaults to null
        val mapped = wire.toActiveSubagent()

        assertNull(wire.todoProgress)
        assertEquals(null, mapped.progress)
        assertFalse(mapped.hasDeterminateProgress)
        assertEquals(0f, mapped.ringFraction)
    }


    @Test
    fun `todo_progress with zero total maps to determinate progress`() {
        val wire = SubagentEntry(
            toolCallId = "toolu_z",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 0, total = 0),
        )
        val mapped = wire.toActiveSubagent()

        assertNotNull(mapped.progress)
        assertEquals(0, mapped.progress?.completed)
        assertEquals(0, mapped.progress?.total)
        // Total 0 → hasDeterminateProgress is false (no meaningful fraction).
        assertFalse(mapped.hasDeterminateProgress)
        assertEquals(0f, mapped.ringFraction)
    }
}
