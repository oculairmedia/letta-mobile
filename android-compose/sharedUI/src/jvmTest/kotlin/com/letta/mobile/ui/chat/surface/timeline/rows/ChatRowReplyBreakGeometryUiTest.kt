@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.ui.chat.surface.timeline.timelineLeadingSpace
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The break between a turn's closing prose and the notice row under it (a Monitor watch, a Monitor
 * event such as a merge-state change, a task's report) is one section break. On a pointer host the
 * prose's reserved hover-actions line is part of that break, not something the break stacks under,
 * so the notice sits no further below the words than that line (and the prose's own padding), with
 * the line still clear of the notice. On a touch host (no line) it is the plain section break.
 */
class ChatRowReplyBreakGeometryUiTest {
    private val prose = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = WORDS, timestamp = T0, runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun notice(notification: UiSubagentNotification): ChatRenderItem =
        single(UiMessage(id = "n", role = "assistant", content = "", timestamp = T1, subagentNotification = notification))

    private val watch = notice(
        UiSubagentNotification("running", "Watch checks", "<event>checks finished</event>", "events: 10", null, "monitor_1"),
    )
    private val mergeEvent = notice(
        UiSubagentNotification("event", "Watch checks", "<event>merge state changed to UNSTABLE</event>", null, null, "monitor_2"),
    )
    private val failedTask = notice(UiSubagentNotification("failed", "Review the diff", "Found two issues.", null, null, "task_1"))

    /** Renders [prose] then [next] at its list leading space for [host]; returns (words, next row). */
    private fun measure(next: ChatRenderItem, nextTag: String, host: LettaHost): Pair<DpRect, DpRect> {
        var result: Pair<DpRect, DpRect>? = null
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides host) {
                    MaterialTheme {
                        Column(Modifier.width(WIDTH.dp)) {
                            RenderRow(prose, rowContext(newestMessageId = "n"))
                            val leading = timelineLeadingSpace(next, prose, pointer = host === LettaHost.Pointer)
                            Box(Modifier.padding(top = leading)) { RenderRow(next, rowContext(newestMessageId = "n")) }
                        }
                    }
                }
            }
            awaitText(WORDS)
            waitForIdle()
            result = words() to onNodeWithTag(nextTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        }
        return result!!
    }

    private fun ComposeUiTest.words(): DpRect =
        onAllNodesWithText(WORDS, substring = true, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

    private fun assertOneBreakOnPointer(next: ChatRenderItem, nextTag: String) {
        val (words, row) = measure(next, nextTag, LettaHost.Pointer)
        val gap = row.top - words.bottom
        val most = ChatRowSpacing.replyActionsLine + ChatRowSpacing.bubblePaddingVertical + TOLERANCE
        assertTrue(gap <= most, "$nextTag sits $gap under the reply's words on pointer; one break is at most $most")
        assertTrue(gap >= ChatRowSpacing.replyActionsLine - TOLERANCE, "$nextTag ($gap under the words) overlaps the hover-actions line")
    }

    private fun assertOneBreakOnTouch(next: ChatRenderItem, nextTag: String) {
        val (words, row) = measure(next, nextTag, LettaHost.Touch)
        val gap = row.top - words.bottom
        val most = ChatRowSpacing.ungrouped + ChatRowSpacing.bubblePaddingVertical + TOLERANCE
        assertTrue(gap <= most, "$nextTag sits $gap under the reply's words on touch; one break is at most $most")
    }

    @Test
    fun aMonitorWatchSitsOneBreakUnderTheReplyOnPointer() = assertOneBreakOnPointer(watch, ChatRowTestTags.MONITOR_WATCHER_LINE)

    @Test
    fun aMonitorEventSitsOneBreakUnderTheReplyOnPointer() = assertOneBreakOnPointer(mergeEvent, ChatRowTestTags.MONITOR_EVENT_LINE)

    @Test
    fun aTaskReportSitsOneBreakUnderTheReplyOnPointer() = assertOneBreakOnPointer(failedTask, ChatRowTestTags.SUBAGENT_NOTIFICATION)

    @Test
    fun aMonitorWatchSitsOneBreakUnderTheReplyOnTouch() = assertOneBreakOnTouch(watch, ChatRowTestTags.MONITOR_WATCHER_LINE)

    @Test
    fun aTaskReportSitsOneBreakUnderTheReplyOnTouch() = assertOneBreakOnTouch(failedTask, ChatRowTestTags.SUBAGENT_NOTIFICATION)

    private companion object {
        const val RUN_ID = "run-1"
        const val T0 = "2026-10-11T16:00:00Z"
        const val T1 = "2026-10-11T16:01:00Z"
        const val WORDS = "That settles the review."
        const val WIDTH = 800
        const val HEIGHT = 600
        val TOLERANCE: Dp = 1.dp
    }
}
