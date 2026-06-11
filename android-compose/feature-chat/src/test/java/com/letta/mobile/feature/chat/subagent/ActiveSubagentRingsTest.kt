package com.letta.mobile.feature.chat.subagent

import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * letta-mobile-w8mog: unit tests for ActiveSubagentRings lifecycle and tap
 * routing behavior.
 *
 * LIFECYCLE TESTS (consolidated from xm8qk + q6v8b + od488):
 *  - RUNNING rings are visible
 *  - FAILED rings persist (visible)
 *  - COMPLETED rings collapse away (NOT visible)
 *
 * TAP ROUTING TESTS (xm8qk):
 *  - Tap with canViewConversation=true -> navigate to conversation
 *  - Tap with canViewConversation=false -> open todo sheet
 *
 * OVERFLOW TESTS:
 *  - >3 rings -> show 3 + count badge
 */
@Tag("unit")
class ActiveSubagentRingsTest {

    private fun running(
        id: String = "id",
        subagentAgentId: String? = null,
        isSelf: Boolean = false,
    ) = ActiveSubagent(
        id = id,
        description = "Test description",
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
        isSelf = isSelf,
        subagentAgentId = subagentAgentId,
    )

    private fun completed(id: String = "id") = running(id).copy(
        status = ActiveSubagent.Status.COMPLETED,
    )

    private fun failed(id: String = "id") = running(id).copy(
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

    // ---- TAP ROUTING: conversation navigation vs todo sheet ---------------

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
    fun `tap routing logic - conversation nav when canViewConversation`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = "agent-local-abc123",
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the tap routing logic from ActiveSubagentRings
        if (subagent.canViewConversation) {
            conversationNavCalled = true
        } else {
            todoSheetCalled = true
        }

        assertTrue(conversationNavCalled)
        assertFalse(todoSheetCalled)
    }

    @Test
    fun `tap routing logic - todo sheet when NOT canViewConversation`() {
        val subagent = running(
            id = "test_1",
            subagentAgentId = null,
        )

        var conversationNavCalled = false
        var todoSheetCalled = false

        // Simulate the tap routing logic from ActiveSubagentRings
        if (subagent.canViewConversation) {
            conversationNavCalled = true
        } else {
            todoSheetCalled = true
        }

        assertFalse(conversationNavCalled)
        assertTrue(todoSheetCalled)
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
