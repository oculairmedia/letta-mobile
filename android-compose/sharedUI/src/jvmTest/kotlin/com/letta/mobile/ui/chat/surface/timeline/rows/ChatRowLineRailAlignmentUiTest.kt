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
import com.letta.mobile.ui.components.disclosure.DisclosureChevronDefaults
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every disclosure and notice line sits on one of two rails, at every width and on touch and pointer.
 * On the header rail (a run's header and its siblings: a background command's report between runs, a
 * Monitor event or watch, between runs or inside one, and a task notification failed or in flight)
 * each line has the run header's line start, glyph start and size, label start, chevron edge and size
 * and height. On the step rail (an open run's work steps: a reasoning, a tool call, a folded command
 * report) each line matches every other step. A report inside an open tool step's details sits flush
 * with the step's details, inside its chevron's edge.
 */
class ChatRowLineRailAlignmentUiTest {
    @Test
    fun everyHeaderRailLineSharesTheRunHeadersColumn() = forEachEnvironment { environment ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(environment) {
                    val context = rowContext(newestMessageId = AFTER_ID)
                    listOf(foldedRun, standaloneReport, monitorEvent, monitorWatch, failedTask, runningTask, runWithMonitorEvent)
                        .forEach { RenderRow(it, context) }
                }
            }
            awaitText("All green.")
            val header = measure(Line.RunHeader)
            val siblings = listOf(
                measure(Line.Report),
                measure(Line.MonitorEvent),
                measure(Line.MonitorWatch),
                measure(Line.Notification),
                measure(Line.Notification, index = 1),
                measure(Line.MonitorEvent, index = 1),
            )
            siblings.forEach { assertMatches(header, it, Rail.Header.measures, environment) }
            // The Monitor event inside a run also sits on that run's own header's column.
            assertMatches(measure(Line.RunHeader, index = 1), siblings.last(), Rail.Header.measures, environment)
        }
    }

    @Test
    fun everyStepRailLineInAnOpenRunMatchesEveryOtherStep() = forEachEnvironment { environment ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(environment) {
                    // A settled run folds by default; the owner's toggle opens it.
                    RenderRow(openRun, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = AFTER_ID))
                }
            }
            awaitText("All green.")
            val reasoning = measure(Line.Reasoning)
            val report = measure(Line.Report)
            val tools = listOf(measure(Line.ToolStep), measure(Line.ToolStep, index = 1))
            assertMatches(reasoning, report, Rail.Step.measures + Measure.GlyphStart + Measure.GlyphSize, environment)
            tools.forEach { assertMatches(reasoning, it, Rail.Step.measures, environment) }
            // The one-line steps share the step line's height; the reasoning's preview makes it taller by design.
            tools.forEach { assertMatches(report, it, setOf(Measure.Height), environment) }
        }
    }

    @Test
    fun aReportInsideAnOpenToolStepSitsFlushWithTheStepsDetails() = forEachEnvironment { environment ->
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent { Host(environment) { RenderRow(toolWithReport, rowContext()) } }
            onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
            awaitText(NESTED_SUMMARY)
            val step = measure(Line.ToolStep)
            val nested = measure(Line.Report)
            val wide = environment.width >= COMPACT_BREAKPOINT
            val detailsStart = step.bounds.left + if (wide) ChatRowSpacing.runStepDetailIndent else 0.dp
            val glyph = nested.glyph!!
            val labelStart = if (wide) step.labelStart else detailsStart
            val expected = listOf(detailsStart, labelStart, ChatRowSpacing.runGlyphSize, ChatRowSpacing.runGlyphSize)
            assertNearAll(expected, listOf(glyph.left, glyph.left, glyph.right - glyph.left, glyph.bottom - glyph.top), environment)
            assertMatches(step, nested, setOf(Measure.ChevronEdge, Measure.ChevronSize, Measure.Height), environment)
        }
    }

    /** A line under test: its row's tag and its glyph's (null for a tool step, whose glyph shows only while it runs). */
    private enum class Line(val row: String, val glyph: String?) {
        RunHeader(ChatRowTestTags.RUN_HEADER, ChatRowTestTags.RUN_GLYPH),
        Report(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH),
        MonitorEvent(ChatRowTestTags.MONITOR_EVENT_LINE, ChatRowTestTags.MONITOR_GLYPH),
        MonitorWatch(ChatRowTestTags.MONITOR_WATCHER_LINE, ChatRowTestTags.MONITOR_GLYPH),
        Notification(ChatRowTestTags.NOTIFICATION_HEADER, ChatRowTestTags.NOTIFICATION_GLYPH),
        Reasoning(ChatRowTestTags.REASONING_TOGGLE, ChatRowTestTags.REASONING_GLYPH),
        ToolStep(ChatRowTestTags.TOOL_CARD_TOGGLE, null),
    }

    /** One edge or size two lines on a rail share. */
    private enum class Measure(val of: (LineGeometry) -> Dp) {
        LineStart({ it.bounds.left }),
        GlyphStart({ it.glyph!!.left - it.bounds.left }),
        GlyphSize({ it.glyph!!.right - it.glyph.left }),
        LabelStart({ it.labelStart - it.bounds.left }),
        LabelHeight({ it.labelHeight }),
        ChevronEdge({ it.chevron.right }),
        ChevronSize({ it.chevron.right - it.chevron.left }),
        Height({ it.bounds.bottom - it.bounds.top }),
    }

    /** What every line on a rail shares. */
    private enum class Rail(val measures: Set<Measure>) {
        Header(Measure.entries.toSet()),
        Step(setOf(Measure.LineStart, Measure.LabelStart, Measure.LabelHeight, Measure.ChevronEdge, Measure.ChevronSize)),
    }

    private class Environment(val width: Dp, val host: LettaHost) {
        override fun toString() = "at $width on $host"
    }

    private class LineGeometry(
        val line: Line,
        val bounds: DpRect,
        val glyph: DpRect?,
        val labelStart: Dp,
        val labelHeight: Dp,
        val chevron: DpRect,
    )

    private fun ComposeUiTest.measure(line: Line, index: Int = 0): LineGeometry {
        val bounds = onAllNodes(hasTestTag(line.row), useUnmergedTree = true)[index].getUnclippedBoundsInRoot()
        val under = hasAnyAncestor(hasTestTag(line.row))
        val glyph = line.glyph?.let { inside(hasTestTag(it) and under, bounds).single() }
        val chevron = inside(hasTestTag(DisclosureChevronDefaults.TestTag) and under, bounds).single()
        val label = inside(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and under, bounds).minBy { it.left }
        return LineGeometry(line, bounds, glyph, label.left, label.bottom - label.top, chevron)
    }

    /** The bounds of the nodes [matcher] finds that lie within [row] (one row of several with the same tag). */
    private fun ComposeUiTest.inside(matcher: SemanticsMatcher, row: DpRect): List<DpRect> {
        val nodes = onAllNodes(matcher, useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }.filter { it.within(row) }
    }

    private fun DpRect.within(row: DpRect): Boolean =
        left >= row.left - TOLERANCE && right <= row.right + TOLERANCE && top >= row.top - TOLERANCE && bottom <= row.bottom + TOLERANCE

    private fun assertMatches(expected: LineGeometry, actual: LineGeometry, measures: Set<Measure>, environment: Environment) {
        val off = measures.filterNot { near(it.of(expected), it.of(actual)) }
            .map { "$it: ${it.of(expected)} vs ${it.of(actual)}" }
        assertEquals(emptyList(), off, "${actual.line} vs ${expected.line} $environment")
    }

    private fun assertNearAll(expected: List<Dp>, actual: List<Dp>, environment: Environment) {
        val off = expected.zip(actual).filterNot { (a, b) -> near(a, b) }
        assertEquals(emptyList(), off, "nested report's glyph (details start, label start, width, height) $environment")
    }

    private fun near(a: Dp, b: Dp): Boolean = abs(a.value - b.value) <= TOLERANCE.value

    private fun forEachEnvironment(check: (Environment) -> Unit) {
        for (width in listOf(360.dp, 700.dp, 760.dp, 1200.dp)) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) check(Environment(width, host))
        }
    }

    @Composable
    private fun Host(environment: Environment, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides environment.host) {
            MaterialTheme {
                Column(Modifier.width(environment.width)) { content() }
            }
        }
    }

    private val bash = UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success")

    private fun step(id: String, runId: String) =
        UiMessage(
            id = id, role = "assistant", content = "", timestamp = T0, runId = runId,
            toolCalls = listOf(bash.copy(toolCallId = "call-$id")),
        )

    private fun notice(id: String, notification: UiSubagentNotification) =
        UiMessage(id = id, role = "assistant", content = "", timestamp = T1, subagentNotification = notification)

    private val foldedRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            step("tool", RUN_ID),
            UiMessage(id = "answer", role = "assistant", content = "Done.", timestamp = T1, runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private val reportNotification = UiSubagentNotification(
        status = "completed",
        summary = "Background command \"Fetch origin/main\" completed",
        result = "$ git fetch origin main\n\n[stdout]\nok",
        usage = null,
        transcriptUri = null,
        taskId = "bash_12",
    )

    private val standaloneReport = single(notice("report", reportNotification))

    private val eventNotification =
        UiSubagentNotification("event", "Watch PR", "<event>#178 unresolved threads: 1</event>", null, null, "monitor_1")

    private val monitorEvent = single(notice("event", eventNotification))

    private val monitorWatch = single(
        UiMessage(
            id = "watch", role = "assistant", content = "", timestamp = T1,
            subagentNotification = eventNotification.copy(status = "running", usage = "events: 2", taskId = "monitor_2"),
        ),
    )

    private val taskNotification = UiSubagentNotification("failed", "Review the diff", "Found two issues.", null, null, "task_1")

    private val failedTask = single(notice("failed", taskNotification))

    private val runningTask = single(
        UiMessage(
            id = "running", role = "assistant", content = "", timestamp = T1,
            subagentNotification = taskNotification.copy(status = "running", taskId = "task_2"),
        ),
    )

    /** A run whose Monitor event arrived mid-run: prose, not a work step, so it shows with the run folded. */
    private val runWithMonitorEvent = ChatRenderItem.RunBlock(
        runId = OTHER_RUN_ID,
        messages = listOf(
            step("watch-tool", OTHER_RUN_ID),
            UiMessage(
                id = "inner-event", role = "assistant", content = "", timestamp = T1, runId = OTHER_RUN_ID,
                subagentNotification = eventNotification.copy(taskId = "monitor_3"),
            ),
            UiMessage(id = AFTER_ID, role = "assistant", content = "All green.", timestamp = T2, runId = OTHER_RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private val openRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(
                id = "thought", role = "assistant", content = "Check the remote first.", timestamp = T0, runId = RUN_ID,
                isReasoning = true,
            ),
            step("before", RUN_ID),
            notice("report", reportNotification).copy(runId = RUN_ID),
            step("again", RUN_ID),
            UiMessage(id = AFTER_ID, role = "assistant", content = "All green.", timestamp = T2, runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private val toolWithReport = single(
        UiMessage(
            id = "nested", role = "assistant", content = "", timestamp = T0,
            toolCalls = listOf(
                bash.copy(
                    arguments = """{"command":"wait-for-agent"}""",
                    result = "<task-notification><task-id>task_7</task-id><status>completed</status>" +
                        "<summary>$NESTED_SUMMARY</summary><result>All done.</result></task-notification>",
                ),
            ),
        ),
    )

    private companion object {
        const val RUN_ID = "run-rails"
        const val OTHER_RUN_ID = "run-rails-monitor"
        const val AFTER_ID = "after"
        const val NESTED_SUMMARY = "Agent finished the review"
        const val T0 = "2026-10-11T16:00:00Z"
        const val T1 = "2026-10-11T16:01:00Z"
        const val T2 = "2026-10-11T16:02:00Z"
        val COMPACT_BREAKPOINT = 600.dp
        val TOLERANCE = 0.5.dp
    }
}
