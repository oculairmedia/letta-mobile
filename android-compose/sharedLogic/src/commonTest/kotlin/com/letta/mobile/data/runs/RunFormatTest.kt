package com.letta.mobile.data.runs

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunFormatTest {
    @Test
    fun durationsReadInTheirUnit() {
        assertNull(RunFormat.duration(null))
        assertEquals("850 ms", RunFormat.duration(850_000_000))
        assertEquals("4.2 s", RunFormat.duration(4_210_000_000))
        assertEquals("3 m 12 s", RunFormat.duration(192_000_000_000))
    }

    @Test
    fun aRowSaysItsStatusStopReasonAndDuration() {
        val run = Run(id = "run-1", agentId = "a", status = "failed", stopReason = "error", totalDurationNs = 850_000_000)
        assertEquals("failed · error · 850 ms", RunFormat.summaryLine(run))
        assertEquals("completed", RunFormat.summaryLine(Run(id = "r", agentId = "a", status = "completed", stopReason = "completed")))
        assertEquals("unknown", RunFormat.summaryLine(Run(id = "r", agentId = "a")))
    }

    @Test
    fun idsAndStepsAreShortened() {
        assertEquals("…12345678", RunFormat.shortId("run-0000-12345678"))
        assertEquals("run-1", RunFormat.shortId("run-1"))
        assertEquals("gpt · 12 tokens · completed", RunFormat.stepLine(Step(id = "s", model = "gpt", promptTokens = 5, completionTokens = 7, status = "completed")))
        assertEquals("unknown", RunFormat.stepLine(Step(id = "s")))
    }
}
