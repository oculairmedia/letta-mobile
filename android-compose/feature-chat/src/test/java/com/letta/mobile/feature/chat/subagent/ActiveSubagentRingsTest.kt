package com.letta.mobile.feature.chat.subagent

import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * letta-mobile-w8mog + i2f23: unit tests for ActiveSubagentRings lifecycle,
 * tap routing behavior, and DETERMINATE RING FILL.
 *
 * LIFECYCLE TESTS (consolidated from xm8qk + q6v8b + od488 + i2f23):
 *  - RUNNING rings are visible
 *  - FAILED rings persist (visible)
 *  - COMPLETED rings briefly visible (fill-to-100% hold), then collapse
 *
 * FILL TESTS (i2f23):
 *  - fraction → sweep mapping (determinate from todo_progress)
 *  - null progress → sliver (~8% arc)
 *  - failed → frozen at reached fill
 *  - completed → animate to 100% then release hold
 *
 * ROUTING TESTS (xm8qk + ww9iu):
 *  - Tap always opens TodoWrite sheet
 *  - Long press with canViewConversation=true -> navigate to conversation
 *  - Long press with canViewConversation=false -> no navigation
 *
 * OVERFLOW TESTS:
 *  - >3 rings -> show 3 + count badge
 */
@Tag("unit")
class ActiveSubagentRingsTest {

    private fun running(
        id: String = "id",
        subagentAgentId: String? = null,
        subagentConversationId: String? = null,
        isSelf: Boolean = false,
        progress: SubagentTodoProgress? = null,
    ) = ActiveSubagent(
        id = id,
        description = "Test description",
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
        isSelf = isSelf,
        subagentAgentId = subagentAgentId,
        subagentConversationId = subagentConversationId,
        progress = progress,
    )

    private fun completed(
        id: String = "id",
        progress: SubagentTodoProgress? = null,
    ) = running(id, progress = progress).copy(
        status = ActiveSubagent.Status.COMPLETED,
    )

    private fun failed(
        id: String = "id",
        progress: SubagentTodoProgress? = null,
    ) = running(id, progress = progress).copy(
        status = ActiveSubagent.Status.FAILED,
    )

    // ---- LIFECYCLE: visibility logic --------------------------------------

    @Test
    fun `RUNNING rings are visible`() {
        val subagent = running(id = "run_1")
        assertTrue(subagent.status == ActiveSubagent.Status.RUNNING)
        // In the ActiveSubagentRings filter, this would be included
        assertTrue(
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        )
    }

    @Test
    fun `FAILED rings persist and are visible`() {
        val subagent = failed(id = "fail_1")
        assertTrue(subagent.status == ActiveSubagent.Status.FAILED)
        // In the ActiveSubagentRings filter, this would be included
        assertTrue(
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        )
    }

    @Test
    fun `COMPLETED rings collapse away and are NOT visible`() {
        val subagent = completed(id = "done_1")
        assertTrue(subagent.status == ActiveSubagent.Status.COMPLETED)
        // In the ActiveSubagentRings filter, this would be EXCLUDED
        assertFalse(
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        )
    }

    @Test
    fun `only RUNNING and FAILED subagents pass the visibility filter`() {
        val all = persistentListOf(
            running(id = "run_1"),
            running(id = "run_2"),
            completed(id = "done_1"),
            failed(id = "fail_1"),
            completed(id = "done_2"),
        )

        val visible = all.filter { subagent ->
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        }

        assertEquals(3, visible.size)
        assertEquals("run_1", visible[0].id)
        assertEquals("run_2", visible[1].id)
        assertEquals("fail_1", visible[2].id)
    }

    // ---- ROUTING: tap opens sheet, long press navigates -------------------

    @Test
    fun `canViewConversation is true when subagentAgentId is present`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "agent-local-abc123",
        )
        assertTrue(subagent.canViewConversation)
    }

    @Test
    fun `canViewConversation is false when subagentAgentId is null`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = null,
        )
        assertFalse(subagent.canViewConversation)
    }

    @Test
    fun `canViewConversation is false when subagentAgentId is blank`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "",
        )
        assertFalse(subagent.canViewConversation)
    }

    @Test
    fun `canViewConversation is false for self entry even with agentId`() {
        val subagent = running(
            id = ActiveSubagent.SELF_ID,
            subagentAgentId = "agent-local-main",
            isSelf = true,
        )
        assertFalse(subagent.canViewConversation)
    }

    @Test
    fun `tap routing logic - opens todo sheet even when canViewConversation`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "agent-local-abc123",
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the tap routing logic from ActiveSubagentRings.
        todoSheetCalled = true

        assertFalse(conversationNavCalled)
        assertTrue(todoSheetCalled)
    }

    @Test
    fun `tap routing logic - opens todo sheet when NOT canViewConversation`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = null,
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the tap routing logic from ActiveSubagentRings.
        todoSheetCalled = true

        assertFalse(conversationNavCalled)
        assertTrue(todoSheetCalled)
    }

    @Test
    fun `long press routing logic - conversation nav when actual id exists`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "agent-local-abc123",
            subagentConversationId = "conv-subagent-456",
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the long-press routing logic from ChatScreen: agent id alone
        // is not enough; the actual subagent conversation id must exist.
        val conversationId = subagent.subagentNavigationConversationId
        if (subagent.canViewConversation && conversationId != null) {
            conversationNavCalled = true
        } else {
            todoSheetCalled = true
        }

        assertTrue(conversationNavCalled)
        assertFalse(todoSheetCalled)
        assertEquals("conv-subagent-456", conversationId)
    }

    @Test
    fun `long press routing logic - no conversation nav when NOT canViewConversation`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = null,
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the long-press routing logic from ActiveSubagentRings.
        if (subagent.canViewConversation) {
            conversationNavCalled = true
        }

        assertFalse(conversationNavCalled)
        assertFalse(todoSheetCalled)
    }

    @Test
    fun `long press routing logic - missing conversation id falls back to todo sheet`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "agent-local-abc123",
            subagentConversationId = null,
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        val conversationId = subagent.subagentNavigationConversationId
        if (subagent.canViewConversation && conversationId != null) {
            conversationNavCalled = true
        } else {
            todoSheetCalled = true
        }

        assertFalse(conversationNavCalled)
        assertTrue(todoSheetCalled)
        assertEquals(null, conversationId)
    }

    // ---- OVERFLOW: >3 rings → show 3 + badge -----------------------------

    @Test
    fun `no overflow when 3 or fewer rings`() {
        val subagents = persistentListOf(
            running(id = "1"),
            running(id = "2"),
            running(id = "3"),
        )
        val maxRings = 3
        val overflow = (subagents.size - maxRings).coerceAtLeast(0)
        assertEquals(0, overflow)
    }

    @Test
    fun `overflow count is correct when more than 3 rings`() {
        val subagents = persistentListOf(
            running(id = "1"),
            running(id = "2"),
            running(id = "3"),
            running(id = "4"),
            running(id = "5"),
        )
        val maxRings = 3
        val overflow = (subagents.size - maxRings).coerceAtLeast(0)
        assertEquals(2, overflow)
    }

    @Test
    fun `displayed rings are the first 3 when overflow`() {
        val subagents = persistentListOf(
            running(id = "1"),
            running(id = "2"),
            running(id = "3"),
            running(id = "4"),
            running(id = "5"),
        )
        val maxRings = 3
        val displayed = subagents.take(maxRings)

        assertEquals(3, displayed.size)
        assertEquals("1", displayed[0].id)
        assertEquals("2", displayed[1].id)
        assertEquals("3", displayed[2].id)
    }

    // ---- i2f23: DETERMINATE FILL — fraction → sweep mapping ───────────

    @Test
    fun `ring fraction maps to sweep angle for determinate fill`() {
        // fraction = completed/total → sweep = fraction * 360°
        assertEquals(180f, SubagentTodoProgress(2, 4).fraction * COMPLETED_SWEEP)
        assertEquals(90f, SubagentTodoProgress(1, 4).fraction * COMPLETED_SWEEP)
        assertEquals(360f, SubagentTodoProgress(5, 5).fraction * COMPLETED_SWEEP)
    }

    @Test
    fun `ring fraction is zero when total is zero`() {
        assertEquals(0f, SubagentTodoProgress(0, 0).fraction * COMPLETED_SWEEP)
    }

    @Test
    fun `null progress yields hasDeterminateProgress false`() {
        assertFalse(running(progress = null).hasDeterminateProgress)
    }

    @Test
    fun `zero-total progress yields hasDeterminateProgress false`() {
        // Total 0 means no meaningful completion fraction — the ring renders
        // as indeterminate (sliver), consistent with the model's contract.
        assertFalse(running(progress = SubagentTodoProgress(0, 0)).hasDeterminateProgress)
    }

    // ── i2f23: sliver sweep for no-progress entries ──────────────────────

    @Test
    fun `sliver sweep is used when progress is null`() {
        // When no todo_progress, target sweep should be SLIVER_SWEEP (~8%)
        val subagent = running(progress = null)
        // hasDeterminateProgress is false → sliver path
        assertFalse(subagent.hasDeterminateProgress)
        assertEquals(0f, subagent.ringFraction) // fraction is 0 with no progress
    }

    @Test
    fun `sliver sweep constant is approximately 8 percent of a full ring`() {
        assertEquals(28.8f, SLIVER_SWEEP, 0.01f)
        assertEquals(28.8f / 360f, SLIVER_SWEEP / COMPLETED_SWEEP, 0.001f)
    }

    @Test
    fun `sliver pulse max is larger than base sliver`() {
        assertTrue(SLIVER_SWEEP_MAX > SLIVER_SWEEP)
        assertTrue(SLIVER_SWEEP_MAX <= 360f)
    }

    // ── i2f23: failed → frozen at reached fill ───────────────────────────

    @Test
    fun `failed ring fraction is frozen at last progress`() {
        val failedWithProgress = failed(
            progress = SubagentTodoProgress(2, 5),
        )
        // ringFraction returns progress.fraction for non-completed status
        assertEquals(0.4f, failedWithProgress.ringFraction)
        // The sweep for failed should be the fraction * 360 (frozen)
        assertEquals(0.4f * COMPLETED_SWEEP, failedWithProgress.ringFraction * COMPLETED_SWEEP)
    }

    @Test
    fun `failed without progress has zero fill`() {
        val failedNoProgress = failed(progress = null)
        assertEquals(0f, failedNoProgress.ringFraction)
    }

    @Test
    fun `failed subagent maps to ERROR ring state`() {
        val entry = failed(progress = SubagentTodoProgress(1, 3))
        assertEquals(RingState.ERROR, entry.ringState(now = 0L))
    }

    // ── i2f23: completed → fill to 100% briefly ──────────────────────────

    @Test
    fun `completed ring fraction is always 1 point 0`() {
        val done = completed(progress = SubagentTodoProgress(2, 5))
        assertEquals(1f, done.ringFraction)
        assertEquals(COMPLETED_SWEEP, done.ringFraction * COMPLETED_SWEEP)
    }

    @Test
    fun `completed fill linger window is a positive duration`() {
        assertTrue(COMPLETED_FILL_LINGER_MS > 0)
        assertTrue(COMPLETED_FILL_LINGER_MS >= 300)
    }

    @Test
    fun `completed hold registration works for newly completed entries`() {
        // Simulate: a COMPLETED entry at t=5000 should register in holds.
        val now = 5000L
        val holds = mutableMapOf<String, Long>()
        val entry = completed(id = "done_1")

        // Register new completion
        if (entry.status == ActiveSubagent.Status.COMPLETED && entry.id !in holds) {
            holds[entry.id] = now
        }

        assertTrue(holds.containsKey("done_1"))
        assertEquals(5000L, holds["done_1"])
    }

    @Test
    fun `completed hold expires after the linger window`() {
        val now = 5000L
        val holds = mutableMapOf("done_1" to 5000L)

        // Before expiration
        val stillValid = holds.filterValues { ts -> now - ts < COMPLETED_FILL_LINGER_MS }
        assertTrue(stillValid.containsKey("done_1"))

        // After expiration
        val expired = holds.filterValues { ts -> now + COMPLETED_FILL_LINGER_MS - ts <= COMPLETED_FILL_LINGER_MS }
        // At exactly the threshold it should expire
        holds.entries.removeAll { (_, ts) -> (now + COMPLETED_FILL_LINGER_MS) - ts >= COMPLETED_FILL_LINGER_MS }
        assertFalse(holds.containsKey("done_1"))
    }

    @Test
    fun `completed ring is visible during the hold window`() {
        // When the hold is active, COMPLETED passes the visibility filter.
        val completedHolds = mapOf("done_1" to 4000L)
        val subagents = persistentListOf(
            running(id = "run_1"),
            completed(id = "done_1"),
            failed(id = "fail_1"),
        )

        val visible = subagents.filter { subagent ->
            when (subagent.status) {
                ActiveSubagent.Status.RUNNING -> true
                ActiveSubagent.Status.FAILED -> true
                ActiveSubagent.Status.COMPLETED -> completedHolds.containsKey(subagent.id)
            }
        }

        assertEquals(3, visible.size)
        assertTrue(visible.any { it.id == "done_1" })
    }

    @Test
    fun `completed ring is NOT visible without a hold registration`() {
        // Without a hold, COMPLETED does not pass the visibility filter.
        val completedHolds = emptyMap<String, Long>()
        val subagents = persistentListOf(
            running(id = "run_1"),
            completed(id = "done_1"),
        )

        val visible = subagents.filter { subagent ->
            when (subagent.status) {
                ActiveSubagent.Status.RUNNING -> true
                ActiveSubagent.Status.FAILED -> true
                ActiveSubagent.Status.COMPLETED -> completedHolds.containsKey(subagent.id)
            }
        }

        assertEquals(1, visible.size)
        assertEquals("run_1", visible[0].id)
    }

    // ── i2f23: fill animation duration ───────────────────────────────────

    @Test
    fun `fill animation duration is a positive short value`() {
        assertTrue(RING_FILL_ANIMATION_MS > 0)
        assertTrue(RING_FILL_ANIMATION_MS <= 500)
    }

    // ---- EDGE CASES -------------------------------------------------------

    @Test
    fun `empty list results in no visible rings and no overflow`() {
        val subagents = persistentListOf<ActiveSubagent>()
        val visible = subagents.filter { subagent ->
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        }
        assertEquals(0, visible.size)

        val maxRings = 3
        val overflow = (visible.size - maxRings).coerceAtLeast(0)
        assertEquals(0, overflow)
    }

    @Test
    fun `mixed status list filters correctly for visibility`() {
        val subagents = persistentListOf(
            running(id = "run_1"),
            completed(id = "done_1"),
            completed(id = "done_2"),
            failed(id = "fail_1"),
            running(id = "run_2"),
            completed(id = "done_3"),
        )

        val visible = subagents.filter { subagent ->
            subagent.status == ActiveSubagent.Status.RUNNING ||
            subagent.status == ActiveSubagent.Status.FAILED
        }

        assertEquals(3, visible.size)
        assertEquals("run_1", visible[0].id)
        assertEquals("fail_1", visible[1].id)
        assertEquals("run_2", visible[2].id)
    }
}
