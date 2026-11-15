@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A background command's completed report is drawn with a run header's summary line, so a report
 * between two runs lines up with their headers; inside a run it is one of the run's work steps.
 */
class ChatRowCommandReportAlignmentUiTest {
    @Test
    fun aStandaloneReportLinesUpWithARunHeader() {
        for (width in listOf(360.dp, 700.dp, 760.dp, 1200.dp)) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) {
                runDesktopComposeUiTest(width = 1400, height = 900) {
                    setContent {
                        Host(host, width) {
                            val context = rowContext(newestMessageId = "report")
                            RenderRow(foldedRun(), context)
                            RenderRow(ChatRenderItem.Single(report(runId = null), GroupPosition.None), context)
                        }
                    }
                    awaitText("Done.")
                    val label = "at $width on $host"
                    val header = bounds(ChatRowTestTags.RUN_HEADER)
                    val line = bounds(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY)
                    val headerChevron = chevronRight(ChatRowTestTags.RUN_HEADER)
                    assertNear(headerChevron, chevronRight(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY), "chevron $label")
                    val runGlyph = bounds(ChatRowTestTags.RUN_GLYPH)
                    val reportGlyph = bounds(ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH)
                    assertNear(runGlyph.right - runGlyph.left, reportGlyph.right - reportGlyph.left, "glyph width $label")
                    assertNear(runGlyph.bottom - runGlyph.top, reportGlyph.bottom - reportGlyph.top, "glyph height $label")
                    assertNear(runGlyph.left - header.left, reportGlyph.left - line.left, "glyph start $label")
                    assertNear(
                        labelStart(ChatRowTestTags.RUN_HEADER, "Worked") - header.left,
                        labelStart(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, REPORT_SUMMARY) - line.left,
                        "label start $label",
                    )
                    assertNear(header.bottom - header.top, line.bottom - line.top, "line height $label")
                }
            }
        }
    }

    @Test
    fun aFoldedRunHidesItsCommandReport() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setContent {
            Host(LettaHost.Pointer, 760.dp) {
                RenderRow(runWithReport(), rowContext(newestMessageId = "answer"))
            }
        }
        awaitText("All green.")
        onAllNodesWithTag(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY).assertCountEquals(0)
        onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertCountEquals(0)
    }

    @Test
    fun anOpenRunListsItsCommandReportBetweenTheWorkAroundIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setContent {
            Host(LettaHost.Pointer, 760.dp) {
                // A settled run folds by default; the owner's toggle opens it.
                RenderRow(runWithReport(), rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "answer"))
            }
        }
        awaitText("All green.")
        val steps = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes()
        assertEquals(2, steps.size, "one tool step before the report and one after it")
        val report = bounds(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY)
        val before = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[0].getUnclippedBoundsInRoot()
        val after = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[1].getUnclippedBoundsInRoot()
        assertTrue(before.bottom <= report.top && report.bottom <= after.top, "report $report lists between $before and $after")
    }

    @androidx.compose.runtime.Composable
    private fun Host(host: LettaHost, width: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
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

    private fun runWithReport() = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            toolStep("before", "2026-10-11T16:00:00Z"),
            report(runId = RUN_ID),
            toolStep("after", "2026-10-11T16:01:02Z"),
            UiMessage(id = "answer", role = "assistant", content = "All green.", timestamp = "2026-10-11T16:01:09Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun toolStep(id: String, timestamp: String) = UiMessage(
        id = id, role = "assistant", content = "", timestamp = timestamp, runId = RUN_ID,
        toolCalls = listOf(
            UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success", toolCallId = "call-$id"),
        ),
    )

    private fun report(runId: String?) = UiMessage(
        id = "report", role = "assistant", content = "", timestamp = "2026-10-11T16:01:00Z", runId = runId,
        subagentNotification = UiSubagentNotification(
            status = "completed",
            summary = REPORT_SUMMARY,
            result = "$ git fetch origin main\n\n[stdout]\nok",
            usage = null,
            transcriptUri = null,
            taskId = "bash_12",
        ),
    )

    private fun ComposeUiTest.chevronRight(rowTag: String): Dp = onAllNodes(
        hasTestTag(DisclosureChevronDefaults.TestTag) and hasAnyAncestor(hasTestTag(rowTag)),
        useUnmergedTree = true,
    ).also { assertEquals(1, it.fetchSemanticsNodes().size, "one chevron in $rowTag") }[0].getUnclippedBoundsInRoot().right

    private fun ComposeUiTest.labelStart(rowTag: String, text: String): Dp = onAllNodes(
        hasText(text, substring = true) and hasAnyAncestor(hasTestTag(rowTag)),
        useUnmergedTree = true,
    )[0].getUnclippedBoundsInRoot().left

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(abs(expected.value - actual.value) <= 0.5f, "$what: expected $expected, actual $actual (tolerance 0.5 dp)")
    }

    private companion object {
        const val RUN_ID = "run-report"
        const val REPORT_SUMMARY = "Background command \"Fetch and inspect origin/main pairing state\" completed"
    }
}
