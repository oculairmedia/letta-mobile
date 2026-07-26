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
            messages = listOf(message("reasoning") { copy(isReasoning = true) }),
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
                message("reasoning") { copy(isReasoning = true) },
                message("tool") {
                    copy(
                        toolCalls = listOf(
                            successfulToolCall("ok"),
                            failedToolCall("bad"),
                        ),
                    )
                },
                message("answer") { copy(content = "Done", latencyMs = 2_400L) },
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
                message("tools") {
                    copy(
                        toolCalls = listOf(
                            successfulToolCall("one", executionTimeMs = 400L),
                            successfulToolCall("two", executionTimeMs = 600L),
                        ),
                    )
                },
            ),
            isStreaming = false,
        )!!

        assertEquals(RunActivityState.Worked, activity.state)
        assertEquals(1_000L, activity.durationMs)
        assertEquals(2, activity.toolCount)
        assertEquals(0, activity.failureCount)
    }

    @Test
    fun `completed work uses positive timeline span when latency is unavailable`() {
        val activity = projectRunActivity(
            messages = listOf(
                message("start"),
                message("finish") { copy(timestamp = "2026-07-25T12:00:02Z") },
            ),
            isStreaming = false,
        )!!

        assertEquals(2_000L, activity.durationMs)
    }

    @Test
    fun `message failures are counted without double-counting failed tools`() {
        val activity = projectRunActivity(
            messages = listOf(
                message("run-error") { copy(isError = true) },
                message("tool-error") {
                    copy(
                        isError = true,
                        toolCalls = listOf(failedToolCall("bad")),
                    )
                },
            ),
            isStreaming = false,
        )!!

        assertEquals(2, activity.failureCount)
    }

    private fun message(
        id: String,
        customize: UiMessage.() -> UiMessage = { this },
    ): UiMessage = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-07-25T12:00:00Z",
        runId = "run-1",
    ).customize()

    private fun successfulToolCall(
        id: String,
        executionTimeMs: Long? = null,
    ): UiToolCall = toolCall(id).copy(executionTimeMs = executionTimeMs)

    private fun failedToolCall(id: String): UiToolCall =
        toolCall(id).copy(
            result = "failed",
            status = "error",
        )

    private fun toolCall(id: String) = UiToolCall(
        name = "shell",
        arguments = """{"command":"$id"}""",
        result = "done",
        status = "success",
        toolCallId = id,
    )
}
