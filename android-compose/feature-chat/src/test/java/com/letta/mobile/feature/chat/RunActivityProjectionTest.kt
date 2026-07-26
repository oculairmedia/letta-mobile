package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.RunActivityState
import com.letta.mobile.feature.chat.screen.projectRunActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunActivityProjectionTest {
    @Test
    fun `empty timeline has no disclosure`() {
        assertNull(projectRunActivity(emptyList(), isStreaming = false))
    }

    @Test
    fun `streaming reasoning is working and has no duration`() {
        val activity = projectRunActivity(
            messages = listOf(message("reasoning", isReasoning = true)),
            isStreaming = true,
        )!!

        assertEquals(RunActivityState.Working, activity.state)
        assertTrue(activity.isActive)
        assertNull(activity.durationMs)
    }

    @Test
    fun `completed reasoning is thought with run latency and counts`() {
        val activity = projectRunActivity(
            messages = listOf(
                message("reasoning", isReasoning = true),
                message(
                    id = "tool",
                    toolCalls = listOf(
                        toolCall("ok", status = "success"),
                        toolCall("bad", status = "error"),
                    ),
                ),
                message("answer", content = "Done", latencyMs = 2_400L),
            ),
            isStreaming = false,
        )!!

        assertEquals(RunActivityState.Thought, activity.state)
        assertEquals(2_400L, activity.durationMs)
        assertEquals(2, activity.toolCount)
        assertEquals(1, activity.failureCount)
        assertFalse(activity.isActive)
    }

    @Test
    fun `completed tool-only work sums execution duration when timeline has no span`() {
        val activity = projectRunActivity(
            messages = listOf(
                message(
                    id = "tools",
                    toolCalls = listOf(
                        toolCall("one", status = "success", executionTimeMs = 400L),
                        toolCall("two", status = "success", executionTimeMs = 600L),
                    ),
                ),
            ),
            isStreaming = false,
        )!!

        assertEquals(RunActivityState.Worked, activity.state)
        assertEquals(1_000L, activity.durationMs)
        assertEquals(2, activity.toolCount)
        assertEquals(0, activity.failureCount)
    }

    @Test
    fun `message failures are counted without double-counting failed tools`() {
        val activity = projectRunActivity(
            messages = listOf(
                message("run-error", isError = true),
                message(
                    id = "tool-error",
                    isError = true,
                    toolCalls = listOf(toolCall("bad", status = "error")),
                ),
            ),
            isStreaming = false,
        )!!

        assertEquals(2, activity.failureCount)
    }

    private fun message(
        id: String,
        content: String = "",
        isReasoning: Boolean = false,
        isError: Boolean = false,
        latencyMs: Long? = null,
        toolCalls: List<UiToolCall>? = null,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-07-25T12:00:00Z",
        runId = "run-1",
        isReasoning = isReasoning,
        isError = isError,
        latencyMs = latencyMs,
        toolCalls = toolCalls,
    )

    private fun toolCall(
        id: String,
        status: String,
        executionTimeMs: Long? = null,
    ) = UiToolCall(
        name = "shell",
        arguments = """{"command":"$id"}""",
        result = if (status == "success") "done" else "failed",
        status = status,
        executionTimeMs = executionTimeMs,
        toolCallId = id,
    )
}
