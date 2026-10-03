@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: a settled run's summary ("Worked for 9s") and its tool summary ("Ran 2
 * commands") start at the timeline's gutter, flush with the agent's prose, as the Android rows do.
 *
 * letta-mobile-bglj6.1.11: a run is ONE line with ONE chevron: the run's label leads its tool
 * summary's line and never takes a click (on a touch host or a pointer one); the chevron ends at
 * the run's right edge, at phone and desktop widths alike.
 */
class ChatRowRunAlignmentUiTest {
    private fun call(id: String) = UiToolCall(name = "shell", arguments = "ls", result = "ok", status = "success", toolCallId = id)

    private val run = ChatRenderItem.RunBlock(
        runId = "run-1",
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = "", timestamp = "2026-07-19T12:00:00Z", runId = "run-1", toolCalls = listOf(call("c1"), call("c2"))),
            UiMessage(id = "b", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:09Z", runId = "run-1"),
        ).map { it to GroupPosition.None },
    )

    /** A run with no tool calls: its label sits on a line of its own. */
    private val proseRun = ChatRenderItem.RunBlock(
        runId = "run-2",
        messages = listOf(
            UiMessage(id = "p", role = "assistant", content = "Looking into it.", timestamp = "2026-07-19T12:00:00Z", runId = "run-2"),
            UiMessage(id = "q", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:04Z", runId = "run-2"),
        ).map { it to GroupPosition.None },
    )

    private fun ComposeUiTest.show(item: ChatRenderItem, newest: String?, details: ChatToolDetails, widthDp: Int = 400) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    RenderRow(item, rowContext(newestMessageId = newest, toolDetails = details))
                }
            }
        }
    }

    private fun ComposeUiTest.proseLeft(): Float =
        onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT).fetchSemanticsNodes().first().boundsInRoot.left

    @Test
    fun theRunHeaderAndToolSummaryAreFlushWithTheProse() = runComposeUiTest {
        show(run, newest = "b", details = ChatToolDetails.Sheet)
        val prose = proseLeft()
        val header = onNodeWithText("Worked", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(abs(header - prose) < TOLERANCE_PX, "the run header is inset: $header vs the prose at $prose")
    }

    @Test
    fun aRunWithToolsIsOneLineWithOneChevronOnEveryHost() {
        for (details in ChatToolDetails.entries) {
            for (width in WIDTHS_DP) {
                runComposeUiTest {
                    show(run, newest = "b", details = details, widthDp = width)
                    assertEquals(1, onAllNodesWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).fetchSemanticsNodes().size, "$details @ $width")
                    assertEquals(1, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size, "$details @ $width")
                    val header = onNodeWithTag(ChatRowTestTags.RUN_HEADER).fetchSemanticsNode().boundsInRoot
                    val tools = onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).fetchSemanticsNode().boundsInRoot
                    val block = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).fetchSemanticsNode().boundsInRoot
                    // One line: the label sits inside the tool summary's own band, before it.
                    assertTrue(header.center.y in tools.top..tools.bottom, "the label is its own line: $header vs $tools ($details @ $width)")
                    assertTrue(header.right <= tools.left + TOLERANCE_PX, "the label overlaps the summary: $header vs $tools")
                    // The one chevron ends at the run's right edge.
                    assertTrue(abs(tools.right - block.right) < TOLERANCE_PX, "the chevron is inset: ${tools.right} vs ${block.right}")
                    assertTrue(abs(header.left - proseLeft()) < TOLERANCE_PX, "the label is inset ($details @ $width)")
                }
            }
        }
    }

    @Test
    fun theTouchRunSummaryIsNotClickable() = runComposeUiTest {
        show(run, newest = "b", details = ChatToolDetails.Sheet)
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction().not())
        onNodeWithText("Worked", substring = true, useUnmergedTree = true).assert(hasClickAction().not())
    }

    @Test
    fun aRunWithoutToolsHasAPlainLabelFlushWithItsProse() = runComposeUiTest {
        show(proseRun, newest = "q", details = ChatToolDetails.Sheet)
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction().not())
        assertEquals(0, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size)
        val header = onNodeWithTag(ChatRowTestTags.RUN_HEADER).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(header.left - proseLeft()) < TOLERANCE_PX, "the label is inset: ${header.left} vs ${proseLeft()}")
    }

    @Test
    fun anOlderRunDropsItsLabelAndKeepsItsToolSummaryFlush() = runComposeUiTest {
        // Not the newest row (the row context carries no newest id): the settled run reads as its steps alone.
        show(run, newest = null, details = ChatToolDetails.Inline)
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertDoesNotExist()
        val tools = onNodeWithText("Ran 2 commands", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(abs(tools - proseLeft()) < TOLERANCE_PX, "the tool summary is inset: $tools vs the prose at ${proseLeft()}")
    }

    private companion object {
        const val TOLERANCE_PX = 0.5f

        /** A phone, the desktop's docked panel, the desktop's full page. */
        val WIDTHS_DP = listOf(360, 480, 900)
    }
}
