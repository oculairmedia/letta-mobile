package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [runStepDotIcon] — the classification used by
 * `RunBlock`'s timeline gutter to pick a per-step indicator. Color resolution
 * lives in `runStepDotColor` and is verified separately via screenshot tests.
 *
 * letta-mobile-m772.5
 */
class RunStepDotIconTest {

    @Test
    fun `reasoning message classifies as Reasoning`() {
        val msg = baseAssistant().copy(isReasoning = true, content = "thinking…")
        assertEquals(StepDotIcon.Reasoning, msg.runStepDotIcon())
    }

    @Test
    fun `approval request classifies as Approval`() {
        val msg = baseAssistant().copy(
            approvalRequest = UiApprovalRequest(
                requestId = "req-1",
                toolCalls = listOf(
                    UiApprovalToolCall(
                        toolCallId = "tc-1",
                        name = "delete_files",
                        arguments = "{}",
                    ),
                ),
            ),
        )
        assertEquals(StepDotIcon.Approval, msg.runStepDotIcon())
    }

    @Test
    fun `tool calls classify as ToolCall when not reasoning and no approval`() {
        val msg = baseAssistant().copy(
            toolCalls = listOf(
                UiToolCall(name = "search", arguments = "{}", result = null),
            ),
        )
        assertEquals(StepDotIcon.ToolCall, msg.runStepDotIcon())
    }

    @Test
    fun `plain assistant prose classifies as AssistantText`() {
        val msg = baseAssistant().copy(content = "Hello!")
        assertEquals(StepDotIcon.AssistantText, msg.runStepDotIcon())
    }

    @Test
    fun `non-assistant role classifies as Unknown`() {
        val msg = baseAssistant().copy(role = "user", content = "hi")
        assertEquals(StepDotIcon.Unknown, msg.runStepDotIcon())
    }

    @Test
    fun `reasoning takes precedence over tool calls`() {
        val msg = baseAssistant().copy(
            isReasoning = true,
            toolCalls = listOf(
                UiToolCall(name = "search", arguments = "{}", result = null),
            ),
        )
        assertEquals(StepDotIcon.Reasoning, msg.runStepDotIcon())
    }

    @Test
    fun `approval takes precedence over tool calls`() {
        val msg = baseAssistant().copy(
            toolCalls = listOf(
                UiToolCall(name = "search", arguments = "{}", result = null),
            ),
            approvalRequest = UiApprovalRequest(
                requestId = "req-1",
                toolCalls = listOf(
                    UiApprovalToolCall(
                        toolCallId = "tc-1",
                        name = "search",
                        arguments = "{}",
                    ),
                ),
            ),
        )
        assertEquals(StepDotIcon.Approval, msg.runStepDotIcon())
    }

    @Test
    fun `empty tool call list is treated as no tool calls`() {
        val msg = baseAssistant().copy(toolCalls = emptyList())
        assertEquals(StepDotIcon.AssistantText, msg.runStepDotIcon())
    }

    private fun baseAssistant(): UiMessage = UiMessage(
        id = "m-1",
        role = "assistant",
        content = "",
        timestamp = "2026-04-19T12:00:00Z",
        runId = "run-1",
    )
}
