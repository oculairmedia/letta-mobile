package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Programmatic test harness for the TimelineEvent → UiMessage contract that
 * drives the chat UI. Covers every failure mode Emmanuel surfaced during the
 * mge5 series: tool-call bubble showing but missing command text, missing
 * output, approve/reject visible when already approved, reasoning dropped,
 * standalone tool returns leaking as bubbles.
 *
 * letta-mobile-mge5.23. Prefer adding a case here over iterating on a phone.
 */
class TimelineEventToUiMessageTest {

    private fun confirmed(
        messageType: TimelineMessageType,
        content: String = "",
        serverId: String = "msg-1",
        toolCalls: List<ToolCall> = emptyList(),
        approvalRequestId: String? = null,
        approvalDecided: Boolean = false,
        toolReturnContent: String? = null,
        toolReturnIsError: Boolean = false,
    ) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "server-$serverId",
        content = content,
        serverId = serverId,
        messageType = messageType,
        runId = null,
        stepId = null,
        date = Instant.parse("2026-04-19T06:00:00Z"),
        toolCalls = toolCalls,
        approvalRequestId = approvalRequestId,
        approvalDecided = approvalDecided,
        toolReturnContent = toolReturnContent,
        toolReturnIsError = toolReturnIsError,
    )

    @Test
    fun `standalone TOOL_RETURN events map to null`() {
        val ev = confirmed(TimelineMessageType.TOOL_RETURN, content = "output")
        assertNull(timelineEventToUiMessage(ev))
    }

    @Test
    fun `TOOL_CALL with populated arguments surfaces command in UiToolCall`() {
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{\"command\":\"echo hi\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            content = "Bash(...)",
            toolCalls = listOf(tc),
        )
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("assistant", ui.role)
        val calls = ui.toolCalls!!
        assertEquals(1, calls.size)
        assertEquals("Bash", calls[0].name)
        assertEquals("{\"command\":\"echo hi\"}", calls[0].arguments)
        assertNull(calls[0].result)
        assertNull(calls[0].status)
    }

    @Test
    fun `TOOL_CALL with attached tool return surfaces command + output + success status`() {
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{\"command\":\"echo hi\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(tc),
            toolReturnContent = "hi\n",
            toolReturnIsError = false,
        )
        val ui = timelineEventToUiMessage(ev)!!
        val calls = ui.toolCalls!!
        assertEquals("hi\n", calls[0].result)
        assertEquals("success", calls[0].status)
    }

    @Test
    fun `TOOL_CALL with error tool return surfaces error status`() {
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(tc),
            toolReturnContent = "command not found",
            toolReturnIsError = true,
        )
        val ui = timelineEventToUiMessage(ev)!!
        val calls = ui.toolCalls!!
        assertEquals("error", calls[0].status)
    }

    @Test
    fun `approval_request shows approve_reject UI while undecided`() {
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{\"command\":\"ls\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(tc),
            approvalRequestId = "req-1",
            approvalDecided = false,
        )
        val ui = timelineEventToUiMessage(ev)!!
        val req = ui.approvalRequest!!
        assertEquals("req-1", req.requestId)
        assertEquals(1, req.toolCalls.size)
        assertEquals("Bash", req.toolCalls[0].name)
        assertNull(ui.approvalResponse)
    }

    @Test
    fun `decided approval hides approve_reject UI and surfaces decision via tool-call chip`() {
        // letta-mobile-23h5 (regression fix 2026-04-19): once decided we
        // MUST NOT synthesize a standalone UiApprovalResponse — it would
        // short-circuit ChatMessageBubble into ApprovalResponseCard and the
        // rich tool card (with command + output) would never render. The
        // approved state is conveyed via the per-tool-call chip instead.
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{\"command\":\"ls\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(tc),
            approvalRequestId = "req-1",
            approvalDecided = true,
        )
        val ui = timelineEventToUiMessage(ev)!!
        assertNull("approvalRequest must be gone once decided", ui.approvalRequest)
        assertNull("must NOT synthesize approvalResponse on the tool-call event", ui.approvalResponse)
        val call = ui.toolCalls!![0]
        assertEquals("Bash", call.name)
        assertEquals(
            com.letta.mobile.data.model.UiToolApprovalDecision.Approved,
            call.approvalDecision,
        )
    }

    @Test
    fun `tool return attached AND approvalDecided renders full success state`() {
        val tc = ToolCall(id = "toolu_1", name = "Bash", arguments = "{\"command\":\"echo ok\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(tc),
            approvalRequestId = "req-1",
            approvalDecided = true,
            toolReturnContent = "ok\n",
            toolReturnIsError = false,
        )
        val ui = timelineEventToUiMessage(ev)!!
        assertNull(ui.approvalRequest)
        // letta-mobile-23h5: see sibling test — decision lives on the tool
        // call's chip, never on a sibling approvalResponse card.
        assertNull("must NOT synthesize approvalResponse on the tool-call event", ui.approvalResponse)
        val call = ui.toolCalls!![0]
        assertEquals("Bash", call.name)
        assertEquals("{\"command\":\"echo ok\"}", call.arguments)
        assertEquals("ok\n", call.result)
        assertEquals("success", call.status)
        assertEquals(
            com.letta.mobile.data.model.UiToolApprovalDecision.Approved,
            call.approvalDecision,
        )
    }

    @Test
    fun `REASONING events produce assistant bubble with isReasoning true`() {
        val ev = confirmed(
            TimelineMessageType.REASONING,
            content = "I should check the logs first.",
        )
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("assistant", ui.role)
        assertTrue("isReasoning must be true", ui.isReasoning)
        assertEquals("I should check the logs first.", ui.content)
    }

    @Test
    fun `OTHER message type is dropped`() {
        val ev = confirmed(TimelineMessageType.OTHER, content = "something")
        assertNull(timelineEventToUiMessage(ev))
    }

    @Test
    fun `multi-tool call batch attaches return to first call only`() {
        // Server batches two tools in one approval. tool_return is stored
        // against the first id. Subsequent calls get null result.
        val t1 = ToolCall(id = "toolu_a", name = "Bash", arguments = "{\"command\":\"a\"}")
        val t2 = ToolCall(id = "toolu_b", name = "Bash", arguments = "{\"command\":\"b\"}")
        val ev = confirmed(
            TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(t1, t2),
            toolReturnContent = "a-output",
            approvalDecided = true,
        )
        val ui = timelineEventToUiMessage(ev)!!
        val calls = ui.toolCalls!!
        assertEquals(2, calls.size)
        assertEquals("a-output", calls[0].result)
        assertNull(calls[1].result)
    }

    @Test
    fun `empty toolCalls list produces UiMessage without tool bubble`() {
        // Fallback case: assistant text-only message. Used to sanity-check
        // that a bare ASSISTANT event doesn't accidentally synthesize an
        // empty tool bubble.
        val ev = confirmed(TimelineMessageType.ASSISTANT, content = "Hello!")
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("Hello!", ui.content)
        assertNull(ui.toolCalls)
        assertFalse(ui.isReasoning)
    }
}
