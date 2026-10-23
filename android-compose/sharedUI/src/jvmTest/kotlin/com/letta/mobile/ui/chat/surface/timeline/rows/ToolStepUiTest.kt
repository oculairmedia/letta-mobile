@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolOutputStream
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileRequest
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFiles
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFiles
import com.letta.mobile.ui.theme.ChatRowDimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-2ccpv: a tool call is one muted verb-first line that opens to a bounded, wrapped
 * inset; failures are quiet unless they failed; long outputs keep their last lines.
 */
class ToolStepUiTest {
    private fun bash(command: String, result: String?, status: String? = "success") = UiToolCall(
        name = "Bash",
        arguments = """{"command":${JsonPrimitive(command)}}""",
        result = result,
        status = status,
        toolCallId = "call-1",
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(call: UiToolCall) = show { call }

    private fun androidx.compose.ui.test.ComposeUiTest.show(call: () -> UiToolCall) {
        setContent { MaterialTheme { Box(Modifier.width(WIDTH.dp)) { ToolStep(call(), "call-1", rowCallbacks()) } } }
    }

    @Test
    fun aStepReadsAsItsVerbAndTargetAndOpensToItsCommandAndOutput() = runComposeUiTest {
        show(bash("ls -la", "a.txt"))

        onNodeWithText("Ran").assertExists()
        onNodeWithText("ls -la").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_COMMAND, useUnmergedTree = true).assertTextEquals("$ ls -la")
        onNodeWithText("a.txt").assertExists()
    }

    @Test
    fun aNonZeroExitSaysSoOnTheLineAndASuccessIsQuiet() = runComposeUiTest {
        show(bash("make", "boom\nExit code: 2", status = "error"))
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertTextEquals("exited 2")
    }

    @Test
    fun aRunningStepShowsASpinnerAndASettledOneNone() = runComposeUiTest {
        show(bash("sleep 5", result = null, status = null))
        onNodeWithContentDescription("Running", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLongOutputIsBoundedThenScrolls() = runComposeUiTest {
        show(bash("seq 500", (1..500).joinToString("\n")))
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()

        val viewport = onNodeWithTag(ChatRowTestTags.TOOL_INSET_VIEWPORT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(viewport.height <= ChatRowDimens.toolInsetMaxHeight, "the inset grew to ${viewport.height}")
        onNodeWithTag(ChatRowTestTags.TOOL_OUTPUT_TRUNCATED, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aLongLineWrapsInsteadOfScrollingSideways() = runComposeUiTest {
        val line = "x".repeat(LONG_LINE)
        show(bash("echo", line))
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()

        val text = onNodeWithText(line, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(text.right <= WIDTH.dp, "the line ran past the row (right edge ${text.right})")
        assertTrue(text.height > MIN_WRAPPED_HEIGHT.dp, "the line did not wrap (height ${text.height})")
    }

    @Test
    fun theOutputWindowKeepsTheLastLinesAndCountsTheRest() {
        val window = OutputWindow.tail("a\nb\nc\nd\n\n", maxLines = 2)
        assertEquals("c\nd", window.text)
        assertEquals(2, window.visibleLines)
        assertEquals(2, window.hiddenLines)
        val short = OutputWindow.tail("only", maxLines = 2)
        assertEquals("only", short.text)
        assertEquals(0, short.hiddenLines)
    }

    @Test
    fun stderrReadsInsideTheSameInset() = runComposeUiTest {
        val inset = ToolInset.Command("/repo", "make", listOf(ToolOutputStream(ToolOutputStream.Kind.Stderr, "error: nope")))
        setContent { MaterialTheme { ToolInsetView(inset) } }
        onNodeWithTag(ChatRowTestTags.TOOL_COMMAND, useUnmergedTree = true).assertTextEquals("/repo $ make")
        onNodeWithText("error: nope").assertExists()
    }

    @Test
    fun theKeyboardOpensAndClosesAFocusedStep() = runComposeUiTest {
        show(bash("ls", "a.txt"))
        val line = onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
        line.requestFocus()
        line.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        line.performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        line.performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
    }

    @Test
    fun aRunningStepsOutputFollowsItsTailAsItGrows() = runComposeUiTest {
        var call by mutableStateOf(bash("tail -f log", (1..5).joinToString("\n"), status = null))
        show { call }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        runOnIdle { call = call.copy(result = (1..400).joinToString("\n")) }
        waitForIdle()
        val range = onNodeWithTag(ChatRowTestTags.TOOL_INSET_VIEWPORT, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the output never outgrew its viewport")
        assertEquals(range.maxValue(), range.value(), "the viewport did not follow the output's tail")
    }

    @Test
    fun scrollingUpStopsFollowingTheTailAndScrollingBackToTheEndResumesIt() = runComposeUiTest {
        var call by mutableStateOf(bash("tail -f log", lines(50), status = null))
        show { call }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        val viewport = onNodeWithTag(ChatRowTestTags.TOOL_INSET_VIEWPORT, useUnmergedTree = true)
        fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

        // The reader scrolls up: new output no longer pulls them down.
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -SCROLL_UP_PX) }
        val readingAt = range().value()
        runOnIdle { call = call.copy(result = lines(100)) }
        waitForIdle()
        assertEquals(readingAt, range().value(), "the viewport moved while the reader was scrolled up")
        assertTrue(range().value() < range().maxValue())

        // Back at the end: following resumes.
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range().maxValue()) }
        runOnIdle { call = call.copy(result = lines(150)) }
        waitForIdle()
        assertEquals(range().maxValue(), range().value(), "following did not resume at the end")
    }

    @Test
    fun aTruncatedOutputOpensInFullInADetailPane() = runComposeUiTest {
        show(bash("seq 500", lines(500)))
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_FULL_OUTPUT).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_OPEN_FULL_OUTPUT, useUnmergedTree = true).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_FULL_OUTPUT).assertExists()
        onNodeWithText("1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPathInTheOutputOpensTheFileAtItsLine() = runComposeUiTest {
        val opened = mutableListOf<WorkspaceFileRequest>()
        val files = WorkspaceFiles(WorkspaceFileOpener { opened += it }, MutableStateFlow(WorkspaceFileViewerState()), NoViewer)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWorkspaceFiles provides files) {
                    Box(Modifier.width(WIDTH.dp)) { ToolStep(bash("grep -n x", "src/Main.kt:12: x"), "call-1", rowCallbacks()) }
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        val output = onNodeWithText("src/Main.kt:12: x", useUnmergedTree = true)
        output.performFirstLinkClick()
        runOnIdle { assertEquals(listOf(WorkspaceFileRequest("src/Main.kt", lines = 12..12)), opened) }
    }

    @Test
    fun aFileChipOpensItsFileAndASubagentChipOpensTheSubagent() = runComposeUiTest {
        val opened = mutableListOf<WorkspaceFileRequest>()
        var subagent: ChatSubagentTarget? = null
        val files = WorkspaceFiles(WorkspaceFileOpener { opened += it }, MutableStateFlow(WorkspaceFileViewerState()), NoViewer)
        val call = UiToolCall(name = "Agent", arguments = "{}", result = "done", status = "success", toolCallId = "call-9")
        val line = ToolStepLine(
            ToolVerb.Delegated,
            target = "Audit",
            previews = listOf(ToolPreviewTarget.File("/repo/a.kt", 3..4), ToolPreviewTarget.Subagent("agent-sub")),
            presenterId = "test",
        )
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWorkspaceFiles provides files) {
                    ToolPreviewChips(call, line, rowCallbacks(openSubagent = { subagent = it }))
                }
            }
        }
        onNodeWithText("a.kt #L3-4").performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_PREVIEW_CHIP).performClick()
        runOnIdle {
            assertEquals(listOf(WorkspaceFileRequest("/repo/a.kt", lines = 3..4)), opened)
            assertEquals(ChatSubagentTarget("call-9", "Audit", "agent-sub"), subagent)
        }
    }

    private object NoViewer : WorkspaceFileViewerActions {
        override fun close() = Unit
        override fun retry() = Unit
    }

    private fun lines(count: Int) = (1..count).joinToString("\n")

    private companion object {
        const val WIDTH = 320
        const val SCROLL_UP_PX = 300f
        const val LONG_LINE = 400
        const val MIN_WRAPPED_HEIGHT = 40
    }
}
