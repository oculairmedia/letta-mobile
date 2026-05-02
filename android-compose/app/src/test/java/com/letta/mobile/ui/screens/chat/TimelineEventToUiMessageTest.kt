package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import org.junit.jupiter.api.Tag

/**
 * Programmatic test harness for the TimelineEvent → UiMessage contract that
 * drives the chat UI. Covers every failure mode Emmanuel surfaced during the
 * mge5 series: tool-call bubble showing but missing command text, missing
 * output, approve/reject visible when already approved, reasoning dropped,
 * standalone tool returns leaking as bubbles.
 *
 * letta-mobile-mge5.23. Prefer adding a case here over iterating on a phone.
 */
@Tag("integration")
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
        source: MessageSource = MessageSource.LETTA_SERVER,
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
        source = source,
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
    fun `confirmed reasoning and assistant with same server id produce unique ui ids`() {
        val reasoning = timelineEventToUiMessage(
            confirmed(
                TimelineMessageType.REASONING,
                content = "thinking",
                serverId = "shared-step-id",
                source = MessageSource.CLIENT_MODE_HARNESS,
            )
        )!!
        val assistant = timelineEventToUiMessage(
            confirmed(
                TimelineMessageType.ASSISTANT,
                content = "answer",
                serverId = "shared-step-id",
                source = MessageSource.CLIENT_MODE_HARNESS,
            )
        )!!

        assertTrue(
            "Reasoning and assistant messages sharing a Letta server id need distinct UI ids so render dedupe does not drop the final answer",
            reasoning.id != assistant.id,
        )
        assertEquals("shared-step-id:REASONING", reasoning.id)
        assertEquals("shared-step-id:ASSISTANT", assistant.id)
    }

    @Test
    fun `confirmed events thread run id and step id onto ui message`() {
        val ev = TimelineEvent.Confirmed(
            position = 1.0,
            otid = "server-msg-1",
            content = "thinking",
            serverId = "msg-1",
            messageType = TimelineMessageType.REASONING,
            runId = "run-live",
            stepId = "step-live",
            date = Instant.parse("2026-04-19T06:00:00Z"),
            toolCalls = emptyList(),
            approvalRequestId = null,
            approvalDecided = false,
            toolReturnContent = null,
            toolReturnIsError = false,
        )

        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("run-live", ui.runId)
        assertEquals("step-live", ui.stepId)
    }

    @Test
    fun `OTHER message type is dropped`() {
        val ev = confirmed(TimelineMessageType.OTHER, content = "something")
        assertNull(timelineEventToUiMessage(ev))
    }

    /**
     * letta-mobile-e75s: when a brand-new conversation is opened (Letta
     * `POST /v1/conversations`), the server seeds the message log with a
     * single `system_message` carrying the agent's base instructions. Pre-fix,
     * that arrived through the timeline as a Confirmed/SYSTEM event and the
     * mapper rendered it with role="system" — populating the UI with
     * "miscellaneous message history" before the user had even sent anything
     * (Emmanuel report 2026-04-28). The chat surface is for user-facing
     * conversation; agent-state system messages must not leak into it.
     */
    @Test
    fun `SYSTEM Confirmed events are dropped (e75s)`() {
        val ev = confirmed(
            TimelineMessageType.SYSTEM,
            content = "You are a helpful assistant. Base instructions...",
            serverId = "system-seed-1",
        )
        assertNull(
            "system_message events must not project into the chat UI",
            timelineEventToUiMessage(ev),
        )
    }

    @Test
    fun `SYSTEM Local events are dropped (e75s, defense-in-depth)`() {
        // We don't currently synthesize SYSTEM Locals on the client, but if
        // any future code path does (e.g. an offline-state bubble), the chat
        // projection still drops them — the bug class is "agent-state system
        // text shown as a chat bubble", regardless of source.
        val ev = TimelineEvent.Local(
            position = 1.0,
            otid = "local-system-1",
            content = "Base instructions...",
            sentAt = Instant.parse("2026-04-19T06:00:00Z"),
            deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
            messageType = TimelineMessageType.SYSTEM,
        )
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

    /**
     * lettabot-y4j regression test: USER bubbles must have any leaked
     * <system-reminder>...</system-reminder> envelope blocks stripped at
     * render time. The Letta server persists envelope-wrapped user
     * messages and Android reads them via direct GET, bypassing the
     * lettabot REST scrub. The defensive strip in scrubUserEnvelope must
     * remove the block while preserving the user's actual prompt text.
     */
    @Test
    fun `user message with leaked system-reminder envelope has envelope stripped`() {
        val leaked = "<system-reminder>\n## Message Metadata\n- Channel: Matrix\n- Sender: Emmanuel\n</system-reminder>\n\nhi there"
        val ev = confirmed(TimelineMessageType.USER, content = leaked)
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("user", ui.role)
        assertEquals("hi there", ui.content)
    }

    @Test
    fun `user message that is envelope-only after strip is dropped entirely`() {
        // No body text outside the envelope -> drop the bubble.
        val envelopeOnly = "<system-reminder>\n## Session Context\n- Agent: PM\n</system-reminder>"
        val ev = confirmed(TimelineMessageType.USER, content = envelopeOnly)
        assertNull(timelineEventToUiMessage(ev))
    }

    @Test
    fun `assistant message with system-reminder-like text is NOT scrubbed`() {
        // Only USER bubbles get scrubbed. An assistant explaining the
        // envelope mechanism shouldn't have its content mutilated.
        val text = "Lettabot wraps user input in <system-reminder>blocks</system-reminder>."
        val ev = confirmed(TimelineMessageType.ASSISTANT, content = text)
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals(text, ui.content)
    }

    @Test
    fun `user message without envelope passes through unchanged`() {
        val ev = confirmed(TimelineMessageType.USER, content = "just a normal message")
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("just a normal message", ui.content)
    }

    @Test
    fun `user message with multiple envelope blocks has all stripped`() {
        // Defensive against future formatter changes that emit multiple
        // blocks (e.g. one for session context, one for metadata).
        val leaked = "<system-reminder>a</system-reminder>real text<system-reminder>b</system-reminder>"
        val ev = confirmed(TimelineMessageType.USER, content = leaked)
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("real text", ui.content)
    }

    /**
     * Real-world case from Emmanuel's screenshot in the
     * "Letta Mobile Admin" conversation: Letta Code injects three
     * back-to-back system-reminder envelopes in a single user turn
     * (device info + agent info + permission mode). The first envelope
     * is preceded by the lettabot wrapper's own `<system-reminder>` open
     * — so the persisted form has a stray closing tag separating
     * lettabot's envelope end from Letta Code's envelope start, and a
     * naive non-greedy single-block regex leaves orphan tags visible.
     */
    @Test
    fun `user message with three back-to-back envelope blocks has all stripped`() {
        val leaked = """
            <system-reminder>
            ## Message Metadata
            - Channel: Matrix
            </system-reminder>
            <system-reminder>
            This is automated info about you.
            - Agent ID: agent-d53a5c94
            </system-reminder>
            <system-reminder>Permission mode active: bypassPermissions.</system-reminder>
            actual user prompt here
        """.trimIndent()
        val ev = confirmed(TimelineMessageType.USER, content = leaked)
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("actual user prompt here", ui.content)
    }

    @Test
    fun `user message with orphan closing tag has it stripped`() {
        // Defends against the malformed-envelope shape where lettabot's
        // wrapping closes BEFORE Letta Code's nested envelope opens,
        // leaving a stray `</system-reminder>` mid-content.
        val leaked = "</system-reminder>\n<system-reminder>X</system-reminder>\nbody"
        val ev = confirmed(TimelineMessageType.USER, content = leaked)
        val ui = timelineEventToUiMessage(ev)!!
        assertEquals("body", ui.content)
    }

    @Test
    fun `user message with only orphan tags and no content is dropped`() {
        val ev = confirmed(TimelineMessageType.USER, content = "</system-reminder>\n<system-reminder>")
        assertNull(timelineEventToUiMessage(ev))
    }
}
