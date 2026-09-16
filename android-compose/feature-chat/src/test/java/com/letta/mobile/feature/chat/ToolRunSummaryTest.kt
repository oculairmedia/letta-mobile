package com.letta.mobile.feature.chat

import com.letta.mobile.data.chat.projection.ToolTimelineCall
import com.letta.mobile.data.chat.projection.ToolTimelineGroup
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.feature.chat.screen.formatElapsedSeconds
import com.letta.mobile.feature.chat.screen.label
import com.letta.mobile.feature.chat.screen.summarizeToolRun
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRunSummaryTest {
    @Test
    fun completedCallsCollapseIntoOneRunLabel() {
        val summary = summarizeToolRun(
            listOf(group(call("a"), call("b"), call("c"), call("d"))),
        )

        assertEquals("Ran 4 commands", summary.label())
    }

    @Test
    fun liveAndHydratedCallsUseTheSameSummaryWithOnlyLifecycleChanging() {
        val live = summarizeToolRun(listOf(group(call("a", ToolTimelineState.Running), call("b"))))
        val hydrated = summarizeToolRun(listOf(group(call("a"), call("b"))))

        assertEquals("Running Bash - 2 commands - 0:18", live.label(elapsedSeconds = 18))
        assertEquals("Ran 2 commands", hydrated.label())
    }

    @Test
    fun approvalAndFailureRemainVisibleInTheCollapsedLine() {
        val approval = summarizeToolRun(listOf(group(call("a", ToolTimelineState.AwaitingApproval))))
        val failed = summarizeToolRun(listOf(group(call("a"), call("b", ToolTimelineState.Failed))))

        assertEquals("Approval needed - 1 command", approval.label())
        assertEquals("2 ran - 1 failed", failed.label())
    }

    @Test
    fun elapsedTimeFormatsBeyondOneMinute() {
        assertEquals("2:05", formatElapsedSeconds(125))
    }

    private fun group(vararg calls: ToolTimelineCall) = ToolTimelineGroup(
        key = "run-tools",
        calls = calls.toList(),
        state = calls.firstOrNull { it.state == ToolTimelineState.Running }?.state
            ?: calls.firstOrNull { it.state == ToolTimelineState.AwaitingApproval }?.state
            ?: ToolTimelineState.Succeeded,
    )

    private fun call(
        id: String,
        state: ToolTimelineState = ToolTimelineState.Succeeded,
    ) = ToolTimelineCall(
        key = id,
        toolCallId = id,
        name = "Bash",
        arguments = "{}",
        result = if (state == ToolTimelineState.Running || state == ToolTimelineState.AwaitingApproval) null else "ok",
        state = state,
        summary = "Bash(command-$id)",
    )
}
