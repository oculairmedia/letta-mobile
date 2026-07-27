package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UsageStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatUsageStatisticsTest {

    @Test
    fun absentWhenTimestampsAreNull() {
        val result = projectChatUsageStatistics(
            startEpochMs = null,
            firstTokenEpochMs = null,
            completionEpochMs = null,
            completionTokens = null,
        )
        assertNull(result)
    }

    @Test
    fun absentWhenTimestampsNonMonotonic() {
        // firstToken epoch occurs before start epoch -> non-monotonic
        val resultNonMonotonicFirstToken = projectChatUsageStatistics(
            startEpochMs = 2000L,
            firstTokenEpochMs = 1000L,
            completionEpochMs = null,
            completionTokens = null,
        )
        assertNull(resultNonMonotonicFirstToken)

        // completion occurs before firstToken epoch -> non-monotonic duration
        val resultNonMonotonicCompletion = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 2000L,
            completionEpochMs = 1500L,
            completionTokens = 100,
        )
        assertNotNull(resultNonMonotonicCompletion)
        assertEquals(1000L, resultNonMonotonicCompletion.firstTokenLatencyMs)
        assertNull(resultNonMonotonicCompletion.outputTokensPerSecond)
        assertNull(resultNonMonotonicCompletion.generationDurationMs)
    }

    @Test
    fun absentWhenCompletionTokensAreZeroOrNegative() {
        val resultZero = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 1500L,
            completionEpochMs = 6500L,
            completionTokens = 0,
        )
        assertNotNull(resultZero)
        assertEquals(500L, resultZero.firstTokenLatencyMs)
        assertNull(resultZero.completionTokens)
        assertNull(resultZero.outputTokensPerSecond)

        val resultNegative = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 1500L,
            completionEpochMs = 6500L,
            completionTokens = -50,
        )
        assertNotNull(resultNegative)
        assertEquals(500L, resultNegative.firstTokenLatencyMs)
        assertNull(resultNegative.completionTokens)
        assertNull(resultNegative.outputTokensPerSecond)
    }

    @Test
    fun absentWhenDurationIsZeroOrNegative() {
        val resultZeroDuration = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 1500L,
            completionEpochMs = 1500L,
            completionTokens = 100,
        )
        assertNotNull(resultZeroDuration)
        assertEquals(500L, resultZeroDuration.firstTokenLatencyMs)
        assertNull(resultZeroDuration.generationDurationMs)
        assertNull(resultZeroDuration.outputTokensPerSecond)
    }

    @Test
    fun validTimestampsAndTokensProduceMetrics() {
        val stats = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 1500L,
            completionEpochMs = 6500L,
            completionTokens = 100,
        )
        assertNotNull(stats)
        assertTrue(stats.hasMetrics)
        assertEquals(500L, stats.firstTokenLatencyMs)
        assertEquals(0.5, stats.firstTokenLatencySeconds)
        assertEquals(5000L, stats.generationDurationMs)
        assertEquals(100, stats.completionTokens)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun ttftNsOverridesTimestampFirstTokenLatency() {
        val stats = projectChatUsageStatistics(
            startEpochMs = 1000L,
            firstTokenEpochMs = 1500L,
            completionEpochMs = 6500L,
            completionTokens = 100,
            ttftNs = 1_200_000_000L,
        )
        assertNotNull(stats)
        assertEquals(1200L, stats.firstTokenLatencyMs)
        assertEquals(1.2, stats.firstTokenLatencySeconds)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun firstTokenLatencySecondsComputesFractionalSeconds() {
        val stats = ChatUsageStatistics(firstTokenLatencyMs = 2500L)
        assertEquals(2.5, stats.firstTokenLatencySeconds)

        val emptyStats = ChatUsageStatistics(firstTokenLatencyMs = null)
        assertNull(emptyStats.firstTokenLatencySeconds)
    }

    @Test
    fun projectFromIsoStringsParsesValidDates() {
        val stats = projectChatUsageStatistics(
            startTimestampIso = "2026-07-25T12:00:00.000Z",
            firstTokenTimestampIso = "2026-07-25T12:00:01.500Z",
            completionTimestampIso = "2026-07-25T12:00:06.500Z",
            completionTokens = 100,
        )
        assertNotNull(stats)
        assertEquals(1500L, stats.firstTokenLatencyMs)
        assertEquals(5000L, stats.generationDurationMs)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun projectFromIsoStringsHandlesInvalidDates() {
        val stats = projectChatUsageStatistics(
            startTimestampIso = "invalid-date",
            firstTokenTimestampIso = null,
            completionTimestampIso = null,
            completionTokens = 100,
        )
        assertNull(stats)
    }

    @Test
    fun projectFromRunModel() {
        val run = Run(
            id = "run-123",
            agentId = "agent-1",
            createdAt = "2026-07-25T12:00:00Z",
            completedAt = "2026-07-25T12:00:05Z",
            ttftNs = 800_000_000L,
            totalDurationNs = 5_000_000_000L,
        )
        val stats = projectChatUsageStatistics(run = run, completionTokens = 100)
        assertNotNull(stats)
        assertEquals(800L, stats.firstTokenLatencyMs)
        assertEquals(5000L, stats.generationDurationMs)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun projectFromStepModel() {
        val step = Step(
            id = "step-1",
            completionTokens = 50,
        )
        val metrics = StepMetrics(
            id = "step-1",
            stepStartNs = 1_000_000_000L,
            llmRequestStartNs = 1_500_000_000L,
            llmRequestNs = 2_500_000_000L,
        )
        val stats = projectChatUsageStatistics(step = step, stepMetrics = metrics)
        assertNotNull(stats)
        // No first-token timestamp exists on StepMetrics: llmRequestStartNs is when the
        // request was issued, so reporting stepStart -> llmRequestStart as first-token
        // latency would publish queue time under the wrong name. Absence is correct.
        assertNull(stats.firstTokenLatencyMs)
        assertEquals(2500L, stats.generationDurationMs)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun projectFromUsageStatisticsModel() {
        val usage = UsageStatistics(
            id = "usage-1",
            completionTokens = 60,
            date = "2026-07-25T12:00:03Z",
        )
        val stats = projectChatUsageStatistics(
            usage = usage,
            startTimestampIso = "2026-07-25T12:00:00Z",
        )
        assertNotNull(stats)
        assertNull(stats.firstTokenLatencyMs)
        assertEquals(3000L, stats.generationDurationMs)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun projectFromUiMessageList() {
        val messages = listOf(
            UiMessage(
                id = "msg-1",
                role = "user",
                content = "Hello",
                timestamp = "2026-07-25T12:00:00Z",
            ),
            UiMessage(
                id = "msg-2",
                role = "assistant",
                content = "Hi",
                timestamp = "2026-07-25T12:00:01Z",
            ),
            UiMessage(
                id = "msg-3",
                role = "assistant",
                content = "there!",
                timestamp = "2026-07-25T12:00:05Z",
            ),
        )
        val stats = projectChatUsageStatistics(messages = messages, completionTokens = 80)
        assertNotNull(stats)
        assertEquals(1000L, stats.firstTokenLatencyMs)
        assertEquals(4000L, stats.generationDurationMs)
        assertEquals(20.0, stats.outputTokensPerSecond)
    }

    @Test
    fun hasMetricsReturnsFalseWhenBothMetricsNull() {
        val stats = ChatUsageStatistics(firstTokenLatencyMs = null, outputTokensPerSecond = null)
        assertFalse(stats.hasMetrics)
    }
}
