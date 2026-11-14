@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DisclosureChevronDefaults
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every disclosure and notice line sits on one of two rails, at every width and on touch and pointer.
 * On the header rail (a run's header and its siblings: a background command's report between runs, a
 * Monitor event or watch, between runs or inside one, and a task notification completed, failed or in
 * flight) each line has the run header's glyph start and size, label start, chevron edge and size and
 * height. On the step rail (an open run's work steps: a reasoning, a tool call, a folded command report)
 * each line matches every other step. A report inside an open tool step's details sits flush with the
 * step's details, inside its chevron's edge.
 */
class ChatRowLineRailAlignmentUiTest {
    @Test
    fun everyHeaderRailLineSharesTheRunHeadersColumn() = forEachEnvironment { width, host ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(host, width) {
                    val context = rowContext(newestMessageId = "after")
                    RenderRow(foldedRun(), context)
                    RenderRow(single(report(id = "report", runId = null)), context)
                    RenderRow(single(monitor(id = "event", status = "event")), context)
                    RenderRow(single(monitor(id = "watch", status = "running")), context)
                    RenderRow(single(task(id = "failed", status = "failed")), context)
                    RenderRow(single(task(id = "running", status = "running")), context)
                    RenderRow(runWithMonitorEvent(), context)
                }
            }
            awaitText("Done.")
            awaitText("All green.")
            val label = "at $width on $host"
            val header = line(ChatRowTestTags.RUN_HEADER, ChatRowTestTags.RUN_GLYPH, index = 0)
            val siblings = mapOf(
                "command report" to line(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH),
                "Monitor event" to line(ChatRowTestTags.MONITOR_EVENT_LINE, ChatRowTestTags.MONITOR_GLYPH, index = 0),
                "Monitor watch" to line(ChatRowTestTags.MONITOR_WATCHER_LINE, ChatRowTestTags.MONITOR_GLYPH),
                "failed notification" to line(ChatRowTestTags.NOTIFICATION_HEADER, ChatRowTestTags.NOTIFICATION_GLYPH, index = 0),
                "in-flight notification" to line(ChatRowTestTags.NOTIFICATION_HEADER, ChatRowTestTags.NOTIFICATION_GLYPH, index = 1),
                "Monitor event inside a run" to line(ChatRowTestTags.MONITOR_EVENT_LINE, ChatRowTestTags.MONITOR_GLYPH, index = 1),
            )
            siblings.forEach { (name, geometry) -> assertSameLine(header, geometry, "$name vs run header $label") }
            val runInnerHeader = line(ChatRowTestTags.RUN_HEADER, ChatRowTestTags.RUN_GLYPH, index = 1)
            assertSameLine(runInnerHeader, siblings.getValue("Monitor event inside a run"), "Monitor event vs its own run's header $label")
        }
    }

    @Test
    fun everyStepRailLineInAnOpenRunMatchesEveryOtherStep() = forEachEnvironment { width, host ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(host, width) {
                    // A settled run folds by default; the owner's toggle opens it.
                    RenderRow(openRun(), rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "after"))
                }
            }
            awaitText("All green.")
            val label = "at $width on $host"
            val reasoning = line(ChatRowTestTags.REASONING_TOGGLE, ChatRowTestTags.REASONING_GLYPH)
            val report = line(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH)
            assertSameGlyph(reasoning, report, "folded report vs reasoning $label")
            assertNear(reasoning.titleHeight, report.titleHeight, "label line height, folded report vs reasoning $label")
            val tools = listOf(line(ChatRowTestTags.TOOL_CARD_TOGGLE, null, 0), line(ChatRowTestTags.TOOL_CARD_TOGGLE, null, 1))
            val steps = listOf(reasoning, report) + tools
            steps.drop(1).forEach { step ->
                assertNear(reasoning.bounds.left, step.bounds.left, "line start, step rail $label")
                assertSameColumns(reasoning, step, "step rail $label")
            }
            // The one-line steps share the step line's height; the reasoning's preview makes it taller by design.
            tools.forEach { step -> assertNear(report.bounds.height(), step.bounds.height(), "line height, tool step vs report $label") }
        }
    }

    @Test
    fun aReportInsideAnOpenToolStepSitsFlushWithTheStepsDetails() = forEachEnvironment { width, host ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(host, width) { RenderRow(single(toolWithReport()), rowContext()) }
            }
            onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
            awaitText(NESTED_SUMMARY)
            val label = "at $width on $host"
            val step = line(ChatRowTestTags.TOOL_CARD_TOGGLE, null)
            val nested = line(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH)
            val detailIndent = if (width < COMPACT_BREAKPOINT) 0.dp else ChatRowSpacing.runStepDetailIndent
            assertNear(step.bounds.left + detailIndent, nested.glyph!!.left, "glyph start at the details' start $label")
            if (width >= COMPACT_BREAKPOINT) assertNear(step.labelStart, nested.glyph.left, "glyph under the step's label $label")
            assertNear(ChatRowSpacing.runGlyphSize, nested.glyph.width(), "glyph width $label")
            assertNear(ChatRowSpacing.runGlyphSize, nested.glyph.height(), "glyph height $label")
            assertNear(step.chevron.right, nested.chevron.right, "chevron edge $label")
            assertNear(step.chevron.width(), nested.chevron.width(), "chevron size $label")
            assertNear(step.bounds.height(), nested.bounds.height(), "line height $label")
        }
    }

    private class LineGeometry(val bounds: DpRect, val glyph: DpRect?, val labelStart: Dp, val titleHeight: Dp, val chevron: DpRect)

    private fun ComposeUiTest.line(rowTag: String, glyphTag: String?, index: Int = 0): LineGeometry {
        val bounds = onAllNodes(hasTestTag(rowTag), useUnmergedTree = true)[index].getUnclippedBoundsInRoot()
        val glyph = glyphTag?.let { inside(hasTestTag(it) and hasAnyAncestor(hasTestTag(rowTag)), bounds).single() }
        val chevron = inside(hasTestTag(DisclosureChevronDefaults.TestTag) and hasAnyAncestor(hasTestTag(rowTag)), bounds).single()
        val texts = inside(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(rowTag)), bounds)
        val title = texts.minBy { it.left }
        return LineGeometry(bounds, glyph, title.left, title.height(), chevron)
    }

    /** The bounds of the nodes [matcher] finds that lie within [row] (one row of several with the same tag). */
    private fun ComposeUiTest.inside(matcher: SemanticsMatcher, row: DpRect): List<DpRect> {
        val nodes = onAllNodes(matcher, useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }.filter { it.within(row) }
    }

    private fun DpRect.within(row: DpRect): Boolean =
        left >= row.left - TOLERANCE && right <= row.right + TOLERANCE && top >= row.top - TOLERANCE && bottom <= row.bottom + TOLERANCE

    private fun assertSameLine(expected: LineGeometry, actual: LineGeometry, what: String) {
        assertNear(expected.bounds.left, actual.bounds.left, "line start, $what")
        assertSameGlyph(expected, actual, what)
        assertSameColumns(expected, actual, what)
        assertNear(expected.bounds.height(), actual.bounds.height(), "line height, $what")
    }

    private fun assertSameGlyph(expected: LineGeometry, actual: LineGeometry, what: String) {
        val a = expected.glyph!!
        val b = actual.glyph!!
        assertNear(a.left - expected.bounds.left, b.left - actual.bounds.left, "glyph start, $what")
        assertNear(a.width(), b.width(), "glyph width, $what")
        assertNear(a.height(), b.height(), "glyph height, $what")
    }

    private fun assertSameColumns(expected: LineGeometry, actual: LineGeometry, what: String) {
        assertNear(expected.labelStart - expected.bounds.left, actual.labelStart - actual.bounds.left, "label start, $what")
        assertNear(expected.chevron.right, actual.chevron.right, "chevron edge, $what")
        assertNear(expected.chevron.width(), actual.chevron.width(), "chevron width, $what")
        assertNear(expected.chevron.height(), actual.chevron.height(), "chevron height, $what")
    }

    private fun DpRect.width(): Dp = right - left

    private fun DpRect.height(): Dp = bottom - top

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        val near = abs(expected.value - actual.value) <= TOLERANCE.value
        assertTrue(near, "$what: expected $expected, actual $actual (tolerance $TOLERANCE)")
    }

    private fun forEachEnvironment(check: (Dp, LettaHost) -> Unit) {
        for (width in listOf(360.dp, 700.dp, 760.dp, 1200.dp)) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) check(width, host)
        }
    }

    @Composable
    private fun Host(host: LettaHost, width: Dp, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides host) {
            MaterialTheme {
                Column(Modifier.width(width)) { content() }
            }
        }
    }

    private fun foldedRun() = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            toolStep("tool", "2026-10-11T16:00:00Z"),
            UiMessage(id = "answer", role = "assistant", content = "Done.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    /** A run whose Monitor event arrived mid-run: prose, not a work step, so it shows with the run folded. */
    private fun runWithMonitorEvent() = ChatRenderItem.RunBlock(
        runId = OTHER_RUN_ID,
        messages = listOf(
            toolStep("watch-tool", "2026-10-11T16:02:00Z", runId = OTHER_RUN_ID),
            monitor(id = "inner-event", status = "event").copy(runId = OTHER_RUN_ID),
            UiMessage(id = "after", role = "assistant", content = "All green.", timestamp = "2026-10-11T16:02:09Z", runId = OTHER_RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun openRun() = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(
                id = "thought", role = "assistant", content = "Check the remote first.", timestamp = "2026-10-11T16:00:00Z",
                runId = RUN_ID, isReasoning = true,
            ),
            toolStep("before", "2026-10-11T16:00:01Z"),
            report(id = "report", runId = RUN_ID),
            toolStep("again", "2026-10-11T16:01:02Z"),
            UiMessage(id = "after", role = "assistant", content = "All green.", timestamp = "2026-10-11T16:01:09Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun toolStep(id: String, timestamp: String, runId: String = RUN_ID) = UiMessage(
        id = id, role = "assistant", content = "", timestamp = timestamp, runId = runId,
        toolCalls = listOf(
            UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success", toolCallId = "call-$id"),
        ),
    )

    private fun toolWithReport() = UiMessage(
        id = "nested", role = "assistant", content = "", timestamp = "2026-10-11T16:03:00Z",
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"wait-for-agent"}""",
                result = "<task-notification><task-id>task_7</task-id><status>completed</status>" +
                    "<summary>$NESTED_SUMMARY</summary><result>All done.</result></task-notification>",
                status = "success",
                toolCallId = "call-nested",
            ),
        ),
    )

    private fun report(id: String, runId: String?) = notificationMessage(
        id, runId,
        UiSubagentNotification(
            status = "completed",
            summary = "Background command \"Fetch origin/main\" completed",
            result = "$ git fetch origin main\n\n[stdout]\nok",
            usage = null,
            transcriptUri = null,
            taskId = "bash_12",
        ),
    )

    private fun monitor(id: String, status: String) = notificationMessage(
        id, null,
        UiSubagentNotification(status, "Watch PR", "<event>#178 unresolved threads: 1</event>", "events: 2", null, "monitor_$id"),
    )

    private fun task(id: String, status: String) = notificationMessage(
        id, null,
        UiSubagentNotification(status, "Review the diff", "Found two issues.", null, null, "task_$id"),
    )

    private fun notificationMessage(id: String, runId: String?, notification: UiSubagentNotification) = UiMessage(
        id = id, role = "assistant", content = "", timestamp = "2026-10-11T16:01:00Z", runId = runId,
        subagentNotification = notification,
    )

    private companion object {
        const val RUN_ID = "run-rails"
        const val OTHER_RUN_ID = "run-rails-monitor"
        const val NESTED_SUMMARY = "Agent finished the review"
        val COMPACT_BREAKPOINT = 600.dp
        val TOLERANCE = 0.5.dp
    }
}
