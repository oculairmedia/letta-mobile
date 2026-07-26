package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunActivityProjectionTest {
    @Test
    fun emptyTimelineHasNoDisclosure() {
        assertNull(projectRunActivity(emptyList(), isActiveRunStreaming = false))
    }

    @Test
    fun activeStreamingReasoningIsWorkingAndHasNoDuration() {
        val activity = projectRunActivity(
            messages = listOf(message("reasoning") { copy(isReasoning = true) }),
            isActiveRunStreaming = true,
        )!!

        assertEquals(RunActivityState.Working, activity.state)
        assertTrue(activity.isActive)
        assertNull(activity.durationMs)
    }

    @Test
    fun activeStreamingAssistantProseRemainsWorking() {
        val activity = projectRunActivity(
            messages = listOf(message("assistant-prose")),
            isActiveRunStreaming = true,
        )!!

        assertEquals(RunActivityState.Working, activity.state)
        assertTrue(activity.isActive)
    }

    @Test
    fun historicalReasoningDoesNotBecomeActiveDuringLaterStreamingResponse() {
        val historicalRun = runBlock(
            runId = "historical",
            messages = listOf(message("historical-reasoning", runId = "historical") { copy(isReasoning = true) }),
        )
        val activeRun = runBlock(
            runId = "active",
            messages = listOf(message("active-reasoning", runId = "active") { copy(isReasoning = true) }),
        )

        assertFalse(
            isActiveStreamingRenderItem(
                renderItem = historicalRun,
                conversationIsStreaming = true,
                newestMessageId = "active-reasoning",
            ),
        )
        assertTrue(
            isActiveStreamingRenderItem(
                renderItem = activeRun,
                conversationIsStreaming = true,
                newestMessageId = "active-reasoning",
            ),
        )

        val historicalActivity = projectRunActivity(
            messages = historicalRun.messages.map { it.first },
            isActiveRunStreaming = false,
        )!!
        assertEquals(RunActivityState.Thought, historicalActivity.state)
        assertFalse(historicalActivity.isActive)
    }

    @Test
    fun completedReasoningUsesLatencyAndCountsFailures() {
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
            isActiveRunStreaming = false,
        )!!

        assertEquals(RunActivityState.Thought, activity.state)
        assertEquals(2_400L, activity.durationMs)
        assertEquals(2, activity.toolCount)
        assertEquals(1, activity.failureCount)
        assertFalse(activity.isActive)
    }

    @Test
    fun completedToolOnlyWorkSumsExecutionDurationWithoutTimelineSpan() {
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
            isActiveRunStreaming = false,
        )!!

        assertEquals(RunActivityState.Worked, activity.state)
        assertEquals(1_000L, activity.durationMs)
        assertEquals(2, activity.toolCount)
        assertEquals(0, activity.failureCount)
    }

    @Test
    fun completedWorkUsesPositiveTimelineSpanWhenLatencyIsUnavailable() {
        val activity = projectRunActivity(
            messages = listOf(
                message("start"),
                message("finish") { copy(timestamp = "2026-07-25T12:00:02Z") },
            ),
            isActiveRunStreaming = false,
        )!!

        assertEquals(2_000L, activity.durationMs)
    }

    @Test
    fun malformedTimestampIsRejectedBySharedParser() {
        assertNull(parseTimestampEpochMillis("not-a-timestamp"))
        assertEquals(1_753_488_000_000L, parseTimestampEpochMillis("2025-07-26T00:00:00Z"))
    }

    @Test
    fun messageFailuresAreCountedWithoutDoubleCountingFailedTools() {
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
            isActiveRunStreaming = false,
        )!!

        assertEquals(2, activity.failureCount)
    }

    private fun runBlock(
        runId: String,
        messages: List<UiMessage>,
    ) = ChatRenderItem.RunBlock(
        runId = runId,
        messages = messages.map { it to GroupPosition.None },
    )

    private fun message(
        id: String,
        runId: String = "run-1",
        customize: UiMessage.() -> UiMessage = { this },
    ): UiMessage = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-07-25T12:00:00Z",
        runId = runId,
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
