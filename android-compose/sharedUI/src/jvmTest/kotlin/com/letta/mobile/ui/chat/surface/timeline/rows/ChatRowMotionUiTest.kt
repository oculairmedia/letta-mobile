@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.toImmutableSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.19: the shared rows play the legacy chat's motion (tool card unfurl, run
 * label cross-fade) and snap it all under reduced motion.
 */
class ChatRowMotionUiTest {
    private val collapsedTool = UiToolCall(
        name = "shell",
        arguments = "echo hello",
        result = "hello\nworld\nand more output",
        status = "completed",
        toolCallId = "call-1",
    )

    @Composable
    private fun ToolCardUnderTest(reducedMotion: Boolean) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            MaterialTheme {
                Box(Modifier.width(400.dp).testTag(CARD)) { ToolCard(collapsedTool, "call-1", rowCallbacks()) }
            }
        }
    }

    private fun ComposeUiTest.cardHeight(): Float = onNodeWithTag(CARD).getUnclippedBoundsInRoot().height.value

    @Test
    fun toolCardBodyUnfurlsInsteadOfAppearing() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { ToolCardUnderTest(reducedMotion = false) }
        mainClock.advanceTimeByFrame()
        val collapsed = cardHeight()

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val opening = cardHeight()
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        val open = cardHeight()

        assertTrue(open > collapsed, "the body never opened (collapsed=$collapsed, open=$open)")
        assertTrue(opening < open, "the body appeared at full height instead of unfurling (opening=$opening, open=$open)")
    }

    @Test
    fun toolCardBodySnapsOpenUnderReducedMotion() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { ToolCardUnderTest(reducedMotion = true) }
        mainClock.advanceTimeByFrame()

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val firstFrames = cardHeight()
        mainClock.advanceTimeBy(SETTLE_MILLIS)

        assertEquals(cardHeight(), firstFrames, "reduced motion still animated the tool card body")
    }

    @Test
    fun runLabelCrossFadesFromWorkingToItsSettledCopy() = runComposeUiTest {
        var state by mutableStateOf(renderState(isStreaming = true))
        mainClock.autoAdvance = false
        setContent { RunUnderTest(state, reducedMotion = false) }
        mainClock.advanceTimeByFrame()
        assertEquals(1, onAllNodesWithText(WORKING, useUnmergedTree = true).fetchSemanticsNodes().size)

        state = renderState(isStreaming = false)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        // Mid-swap both copies are drawn: the old one fading out over the new one fading in.
        assertEquals(1, onAllNodesWithText(WORKING, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText(WORKED, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)

        mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertEquals(0, onAllNodesWithText(WORKING, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText(WORKED, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun runLabelSwapsAtOnceUnderReducedMotion() = runComposeUiTest {
        var state by mutableStateOf(renderState(isStreaming = true))
        mainClock.autoAdvance = false
        setContent { RunUnderTest(state, reducedMotion = true) }
        mainClock.advanceTimeByFrame()

        state = renderState(isStreaming = false)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertEquals(0, onAllNodesWithText(WORKING, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText(WORKED, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun call(id: String) = UiToolCall(name = "shell", arguments = "ls", result = "ok", status = "success", toolCallId = id)

    private val run = ChatRenderItem.RunBlock(
        runId = "run-1",
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = "", timestamp = "2026-07-19T12:00:00Z", runId = "run-1", toolCalls = listOf(call("c1"))),
            UiMessage(id = "b", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:09Z", runId = "run-1"),
        ).map { it to GroupPosition.None },
    )

    private fun renderState(isStreaming: Boolean) = ChatRenderItemState(
        isStreaming = isStreaming,
        activeApprovalRequestId = null,
        collapsedRunIds = emptySet<String>().toImmutableSet(),
        expandedReasoningMessageIds = emptySet<String>().toImmutableSet(),
    )

    @Composable
    private fun RunUnderTest(state: ChatRenderItemState, reducedMotion: Boolean) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(
                        item = run,
                        context = ChatRowContext(
                            itemState = state,
                            streamingMessageId = "b",
                            newestMessageId = "b",
                            toolDetails = ChatToolDetails.Sheet,
                            capabilities = ChatSurfaceCapabilities.Default,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val CARD = "tool-card-under-test"
        const val WORKING = "Working"
        const val WORKED = "Worked"
        const val SETTLE_MILLIS = 1_000L
    }
}
