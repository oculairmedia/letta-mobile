@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatMessageRow
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.RunBlockRow
import com.letta.mobile.ui.chat.surface.timeline.rows.rowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.rowContext
import com.letta.mobile.ui.chat.surface.timeline.rows.renderState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The same open tool output has identical indentation through every shared timeline caller. */
class TimelineToolDetailsWidthUiTest {
    private val call = UiToolCall(
        name = "Bash", arguments = """{"command":"ls"}""", result = LONG_OUTPUT,
        status = "success", toolCallId = "width-call",
    )
    private val message = UiMessage(
        id = "width-message", role = "assistant", content = "", timestamp = "2026-10-31T12:00:00Z",
        runId = RUN_ID, toolCalls = listOf(call),
    )

    private enum class Path { Run, ToolGroup, Message }

    @Test
    fun compactRunDetailsStartAtTheStepEdge() = verify(Path.Run, PHONE_WIDTH, LettaHost.Touch, 0.dp)

    @Test
    fun compactToolGroupDetailsStartAtTheStepEdge() = verify(Path.ToolGroup, PHONE_WIDTH, LettaHost.Touch, 0.dp)

    @Test
    fun compactMessageDetailsStartAtTheStepEdge() = verify(Path.Message, PHONE_WIDTH, LettaHost.Touch, 0.dp)

    @Test
    fun wideRunDetailsKeepTheirIndent() = verify(Path.Run, WIDE_WIDTH, LettaHost.Pointer, ChatRowSpacing.runStepDetailIndent)

    @Test
    fun wideToolGroupDetailsKeepTheirIndent() =
        verify(Path.ToolGroup, WIDE_WIDTH, LettaHost.Pointer, ChatRowSpacing.runStepDetailIndent)

    @Test
    fun wideMessageDetailsKeepTheirIndent() = verify(Path.Message, WIDE_WIDTH, LettaHost.Pointer, ChatRowSpacing.runStepDetailIndent)

    private fun verify(path: Path, width: Dp, host: LettaHost, expectedIndent: Dp) = runDesktopComposeUiTest(
        width = WINDOW_WIDTH_PX, height = WINDOW_HEIGHT_PX,
    ) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides host) {
                Content(path, width)
            }
        }
        onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[0].performClick()
        onNodeWithText(OUTPUT, substring = true, useUnmergedTree = true).assertExists()
        assertIndent(expectedIndent)
        assertOutputWithin(width)
    }

    @Composable
    private fun Content(path: Path, width: Dp) {
        MaterialTheme {
            Box(Modifier.width(width)) { Row(path) }
        }
    }

    @Composable
    private fun Row(path: Path) {
        val context = rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)))
        val callbacks = rowCallbacks()
        when (path) {
            Path.Run -> RunBlockRow(listOf(message), context, callbacks)
            Path.Message -> ChatMessageRow(message, context, callbacks)
            Path.ToolGroup -> ToolGroupRow(
                group = TimelineRow.ToolGroup(listOf(
                    ChatRenderItem.Single(message, GroupPosition.None),
                    ChatRenderItem.Single(
                        message.copy(id = "companion-message", toolCalls = listOf(call.copy(toolCallId = "companion-call"))),
                        GroupPosition.None,
                    ),
                )),
                contexts = TimelineRowContexts(context, streaming = null),
                callbacks = callbacks,
            )
        }
    }

    private fun ComposeUiTest.assertIndent(expected: Dp) {
        val step = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[0].getUnclippedBoundsInRoot()
        val output = onNodeWithTag(ChatRowTestTags.TOOL_OUTPUT).getUnclippedBoundsInRoot()
        val actual = output.left - step.left
        assertTrue(abs(expected.value - actual.value) <= TOLERANCE_DP, "expected indent $expected, actual $actual")
    }

    /** Long output must wrap inside the row: its right edge never passes the row's own width. */
    private fun ComposeUiTest.assertOutputWithin(width: Dp) {
        val row = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[0].getUnclippedBoundsInRoot()
        val output = onNodeWithTag(ChatRowTestTags.TOOL_OUTPUT).getUnclippedBoundsInRoot()
        assertTrue(output.right <= row.left + width + TOLERANCE_DP.dp, "output overflows: right ${output.right}, row width $width")
    }

    private companion object {
        const val RUN_ID = "width-run"
        const val OUTPUT = "tool-width-output"
        // One unbroken line far wider than any test width, so the output must be width-constrained.
        val LONG_OUTPUT = OUTPUT + " " + "x".repeat(LONG_OUTPUT_CHARS)
        const val LONG_OUTPUT_CHARS = 600
        const val WINDOW_WIDTH_PX = 1200
        const val WINDOW_HEIGHT_PX = 900
        const val TOLERANCE_DP = 0.5f
        val PHONE_WIDTH = 360.dp
        val WIDE_WIDTH = 900.dp
    }
}
