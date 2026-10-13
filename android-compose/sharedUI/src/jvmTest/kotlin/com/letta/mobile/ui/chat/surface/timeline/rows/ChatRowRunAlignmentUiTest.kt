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
 * letta-mobile-bglj6.1: a settled run's tool summary ("Ran 2 commands") starts at the timeline's
 * gutter, flush with the agent's prose, as the Android rows do.
 *
 * letta-mobile-ckmub.1 (hard cut S4): the run's header is the legacy RunActivityDisclosure again: its
 * own line above the steps, its label inset by the legacy xs, and, once a run of several steps
 * settles, the fold that collapses the run to its last step. A one-message run's header is a plain
 * label. The tool summary keeps the run's one tool chevron, ending at the run's right edge.
 *
 * letta-mobile-zxk2n.37: a run with work folds by default under its one summary line; opened, its
 * tool line sits indented under the summary's label.
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

    /** [opened] runs are in the page's toggle set: the person opened them (zxk2n.37: folded by default). */
    private fun ComposeUiTest.show(
        item: ChatRenderItem,
        newest: String?,
        details: ChatToolDetails,
        widthDp: Int = 400,
        opened: Set<String> = setOf("run-1"),
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = opened), newestMessageId = newest, toolDetails = details))
                }
            }
        }
    }

    private fun ComposeUiTest.proseLeft(): Float =
        onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT).fetchSemanticsNodes().first().boundsInRoot.left

    @Test
    fun theRunHeaderSitsOnItsOwnLineWithTheLegacyInset() = runComposeUiTest {
        show(run, newest = "b", details = ChatToolDetails.Sheet)
        val prose = proseLeft()
        val row = onNodeWithTag(ChatRowTestTags.RUN_HEADER).fetchSemanticsNode().boundsInRoot
        val label = onNodeWithText("Worked", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tools = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes().first().boundsInRoot
        assertTrue(abs(row.left - prose) < TOLERANCE_PX, "the run header row is inset: $row vs the prose at $prose")
        assertTrue(label.left > prose + TOLERANCE_PX, "the header's label lost the legacy inset: $label vs $prose")
        assertTrue(tools.top >= label.bottom - TOLERANCE_PX, "the first tool step is not below the header: $tools vs $label")
    }

    @Test
    fun aRunWithToolsListsOneStepRowPerCallOnEveryHost() {
        for (details in ChatToolDetails.entries) {
            for (width in WIDTHS_DP) {
                runComposeUiTest {
                    show(run, newest = "b", details = details, widthDp = width)
                    // ckmub.1.2: no group line; each call is a step row of its own under the summary.
                    assertEquals(0, onAllNodesWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).fetchSemanticsNodes().size, "$details @ $width")
                    assertEquals(0, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size, "$details @ $width")
                    val steps = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes()
                    assertEquals(2, steps.size, "$details @ $width")
                    val block = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).fetchSemanticsNode().boundsInRoot
                    val label = onNodeWithText("Worked", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    steps.forEach { step ->
                        val tools = step.boundsInRoot
                        // The row ends at the run's right edge; the step sits indented under the summary's label.
                        assertTrue(abs(tools.right - block.right) < TOLERANCE_PX, "the step row is inset: ${tools.right} vs ${block.right}")
                        assertTrue(abs(tools.left - label.left) < TOLERANCE_PX, "the tool step is not under the summary's label ($details @ $width)")
                    }
                }
            }
        }
    }

    @Test
    fun aSettledRunOfSeveralStepsFoldsFromItsHeader() = runComposeUiTest {
        show(run, newest = "b", details = ChatToolDetails.Sheet)
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction())
    }

    @Test
    fun aOneMessageRunHasAPlainLabel() = runComposeUiTest {
        val lone = ChatRenderItem.Single(
            UiMessage(id = "s", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:04Z", runId = "run-3"),
            GroupPosition.None,
            stableRunId = "run-3",
        )
        show(lone, newest = "s", details = ChatToolDetails.Sheet)
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction().not())
        assertEquals(0, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size)
    }
    @Test
    fun anOlderRunKeepsItsSummaryLineAndFoldsItsToolLine() = runComposeUiTest {
        // Not the newest row: the summary line is the only way to the folded steps, so it stays.
        show(run, newest = null, details = ChatToolDetails.Inline, opened = emptySet())
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction())
        onNodeWithText("Ran 2 commands", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("All done here.", substring = true).assertExists()
    }

    private companion object {
        const val TOLERANCE_PX = 0.5f

        /** A phone, the desktop's docked panel, the desktop's full page. */
        val WIDTHS_DP = listOf(360, 480, 900)
    }
}
