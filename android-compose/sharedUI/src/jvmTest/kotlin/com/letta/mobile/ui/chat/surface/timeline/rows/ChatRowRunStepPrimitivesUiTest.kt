@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DisclosureChevronDefaults
import com.letta.mobile.ui.components.LettaListRowDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.1.2: a run block is built from the shared primitives. Opened, its tool calls
 * list as step rows straight under the summary line (no "Ran N commands" group row with a chevron
 * of its own); each step is a LettaListRow led by the summary line's own status glyph at the same
 * size, with one DisclosureChevron only when it has details, and its details read in ToolOutputBlock.
 */
class ChatRowRunStepPrimitivesUiTest {
    private fun call(id: String, name: String = "Bash", command: String = "ls", status: String? = "success", result: String? = "ok") =
        UiToolCall(name = name, arguments = """{"command":"$command"}""", result = result, status = status, toolCallId = id)

    private fun runOf(vararg calls: UiToolCall) = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "t", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = RUN_ID, toolCalls = calls.toList()),
            UiMessage(id = "a", role = "assistant", content = "Fixed it.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun ComposeUiTest.showOpened(item: ChatRenderItem, details: ChatToolDetails = ChatToolDetails.Inline) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "a", toolDetails = details))
                }
            }
        }
        awaitText("Fixed it.")
    }

    private fun under(tag: String): SemanticsMatcher = hasAnyAncestor(hasTestTag(tag))

    private fun ComposeUiTest.count(matcher: SemanticsMatcher): Int =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private val chevron = hasTestTag(DisclosureChevronDefaults.TestTag)

    @Test
    fun anOpenedRunHasOneChevronPerDisclosableLevelAndNoGroupRow() {
        for (details in ChatToolDetails.entries) {
            runComposeUiTest {
                showOpened(runOf(call("c1"), call("c2", command = "pwd")), details)

                onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
                onNodeWithTag(ChatRowTestTags.RUN_STEP_SUBHEADER).assertDoesNotExist()
                assertEquals(2, onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes().size, "$details")
                // The summary line's one chevron, and one per step: nothing nested.
                assertEquals(1, count(chevron and under(ChatRowTestTags.RUN_HEADER)), "$details")
                assertEquals(2, count(chevron and under(ChatRowTestTags.TOOL_CARD_TOGGLE)), "$details")
                assertEquals(3, count(chevron), "$details")
            }
        }
    }

    @Test
    fun stepRowsAreTheSharedListRow() = runComposeUiTest {
        showOpened(runOf(call("c1"), call("c2", command = "pwd")))

        assertEquals(2, count(hasTestTag(LettaListRowDefaults.TitleTestTag) and under(ChatRowTestTags.TOOL_CARD_TOGGLE)))
        assertEquals(2, count(hasTestTag(LettaListRowDefaults.TitleTestTag) and under(ChatRowTestTags.RUN_TOOL_STEPS)))
        // Straight under the run's line, no group row between them.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
        // The tool's name over the command's one-line preview.
        assertEquals(2, count(hasText("Bash") and under(ChatRowTestTags.TOOL_CARD_TOGGLE)))
        assertEquals(1, count(hasText("pwd") and under(ChatRowTestTags.TOOL_CARD_TOGGLE)))
    }

    @Test
    fun stepsOfSeveralKindsGetAPlainSubheaderNeverASecondChevron() = runComposeUiTest {
        showOpened(runOf(call("c1"), call("c2", name = "Read", command = "README.md")))

        val subheader = onNodeWithTag(ChatRowTestTags.RUN_STEP_SUBHEADER, useUnmergedTree = true)
        subheader.assertExists()
        subheader.assert(hasClickAction().not())
        assertEquals(0, count(chevron and under(ChatRowTestTags.RUN_STEP_SUBHEADER)))
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
        assertEquals(2, count(chevron and under(ChatRowTestTags.TOOL_CARD_TOGGLE)))
    }

    @Test
    fun theStepGlyphIsTheSummaryGlyphAtTheSameSize() = runComposeUiTest {
        showOpened(runOf(call("c1"), call("c2", status = "error", result = "boom")))

        val summary = onNodeWithTag(ChatRowTestTags.RUN_GLYPH, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val steps = onAllNodesWithTag(ChatRowTestTags.TOOL_STATUS_GLYPH, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, steps.size)
        steps.forEach { step ->
            assertEquals(summary.width, step.boundsInRoot.width, "a step glyph is not the summary glyph's width")
            assertEquals(summary.height, step.boundsInRoot.height, "a step glyph is not the summary glyph's height")
        }
    }

    @Test
    fun anOpenStepReadsItsDetailsInAToolOutputBlock() = runComposeUiTest {
        showOpened(runOf(call("c1", command = "echo hello", result = "hello")))

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        // The command and the output, each in the shared monospace block.
        assertEquals(2, count(hasTestTag(ChatRowTestTags.TOOL_OUTPUT) and under(ChatRowTestTags.TOOL_CARD_BODY)))
        assertEquals(1, count(hasText("$ echo hello") and under(ChatRowTestTags.TOOL_OUTPUT)))
        assertEquals(1, count(hasText("hello") and under(ChatRowTestTags.TOOL_OUTPUT)))
    }

    @Test
    fun aStepWithNothingToShowHasNoChevron() = runComposeUiTest {
        showOpened(runOf(UiToolCall(name = "noop", arguments = "", result = null, status = "success", toolCallId = "c1")))

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertIsNotEnabled()
        assertEquals(0, count(chevron and under(ChatRowTestTags.TOOL_CARD_TOGGLE)))
    }

    @Test
    fun aStepRowKeepsItsHeightAsItSettles() = runComposeUiTest {
        var item by mutableStateOf(runOf(call("c1", status = null, result = null)))
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "a"))
                }
            }
        }
        awaitText("Fixed it.")
        val running = onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNode().boundsInRoot.height
        item = runOf(call("c1"))
        waitForIdle()
        val settled = onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNode().boundsInRoot.height
        assertEquals(running, settled, "the step row changed height as it settled")
        assertTrue(settled > 0f)
    }

    private companion object {
        const val RUN_ID = "run-steps"
    }
}
