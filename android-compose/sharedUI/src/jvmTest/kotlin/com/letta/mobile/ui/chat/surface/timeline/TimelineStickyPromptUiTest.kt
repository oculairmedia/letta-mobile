@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList

/**
 * letta-mobile-bglj6.1: under floating chrome (the phone's status bar and header,
 * ChatSurfacePlatform.topChromeInset) the timeline draws up under the chrome, and the prompt that
 * scrolls off becomes a sticky header at the visible top. It rides its row up to the inset, holds
 * there without ever travelling under the chrome, and takes over from its row without a jump.
 */
class TimelineStickyPromptUiTest {
    private val longAnswer = (1..120).joinToString("\n\n") { "Paragraph $it of a long answer." }
    private val messages = listOf(
        UiMessage(id = "q1", role = "user", content = "First question", timestamp = "2026-09-12T12:00:00Z"),
        UiMessage(id = "a1", role = "assistant", content = longAnswer, timestamp = "2026-09-12T12:00:10Z"),
        UiMessage(id = "q2", role = "user", content = "Second question", timestamp = "2026-09-12T12:01:00Z"),
        UiMessage(id = "a2", role = "assistant", content = "Short answer.", timestamp = "2026-09-12T12:01:10Z"),
    )
    private val state = ChatUiState(
        conversationState = ConversationState.Ready("c1"),
        isLoadingMessages = false,
        messages = messages.toPersistentList(),
    )

    private fun ComposeUiTest.show(listState: LazyListState, topInset: Dp, chrome: Boolean = false) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 640.dp).background(MaterialTheme.colorScheme.background)) {
                    ChatTimeline(
                        state = state,
                        pagedTimeline = null,
                        actions = RecordingChatActions(),
                        capabilities = ChatSurfaceCapabilities.Default,
                        host = ChatSurfaceHost(),
                        appearance = ChatSurfaceAppearance(),
                        listState = listState,
                        topInset = topInset,
                    )
                    // The snapshot shows where the chrome would float (a translucent header band).
                    if (chrome) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(topInset)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CHROME_ALPHA)),
                        )
                    }
                }
            }
        }
        // The markdown lays out off the main thread: wait for the answer's full height first.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("Paragraph 120", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Start at the oldest end: the first prompt rests in view, below the chrome.
        runOnIdle { listState.dispatchRawDelta(listState.layoutInfo.totalItemsCount * FAR_PX) }
        waitForIdle()
        val promptAtRest = promptRowTop(listState)
        val rows = runOnIdle {
            val info = listState.layoutInfo
            info.visibleItemsInfo.map { "${it.key}@${info.viewportEndOffset - it.offset - it.size}" }
        }
        assertTrue(
            promptAtRest != null && promptAtRest >= stickLinePx(topInset),
            "the first prompt starts in view below the chrome: $promptAtRest, rows $rows",
        )
    }

    /** The first prompt's row top in the list, px from its top edge; null off screen. */
    private fun ComposeUiTest.promptRowTop(listState: LazyListState): Float? = runOnIdle {
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key.toString().contains("q1") }
            ?.let { (info.viewportEndOffset - it.offset - it.size).toFloat() }
    }

    /** The pinned copy's top, px from the list's top edge; null while nothing is pinned. */
    private fun ComposeUiTest.pinnedTop(): Float? {
        val pinned = onAllNodesWithTag(ChatTimelineTags.PINNED_PROMPT).fetchSemanticsNodes().firstOrNull() ?: return null
        val list = onNodeWithTag(ChatTimelineTags.LIST).fetchSemanticsNode().boundsInRoot
        return pinned.boundsInRoot.top - list.top
    }

    private fun ComposeUiTest.stickLinePx(topInset: Dp): Float = with(density) { topInset.toPx() }

    @Test
    fun thePromptPinsAtTheInsetWithoutAJumpAndNeverGoesUnderTheChrome() = runComposeUiTest {
        val listState = LazyListState()
        show(listState, TOP_INSET)
        val line = stickLinePx(TOP_INSET)
        var previous: Float? = null
        var heldSteps = 0
        var steps = 0
        while (heldSteps < HELD_STEPS && steps < MAX_STEPS) {
            steps++
            // Scroll toward the newest: the content, the first prompt with it, moves up.
            runOnIdle { listState.dispatchRawDelta(-STEP_PX) }
            waitForIdle()
            val pinned = pinnedTop()
            val shown = pinned ?: promptRowTop(listState) ?: error("the first prompt vanished at step $steps")
            if (pinned != null) {
                assertTrue(pinned >= line - TOLERANCE_PX, "the pinned prompt went under the chrome: $pinned < $line")
                if (abs(pinned - line) <= TOLERANCE_PX) heldSteps++
            }
            previous?.let { last ->
                assertTrue(shown <= last + TOLERANCE_PX, "the prompt moved back down: $last -> $shown at step $steps")
                assertTrue(last - shown <= STEP_PX + TOLERANCE_PX, "the prompt jumped: $last -> $shown at step $steps")
            }
            previous = shown
        }
        assertTrue(heldSteps >= HELD_STEPS, "the prompt never held at the inset ($steps steps, last at $previous, line $line)")
    }

    @Test
    fun withoutChromeThePromptPinsOnlyOnceItsRowHasLeft() = runComposeUiTest {
        val listState = LazyListState()
        show(listState, 0.dp)
        // Without chrome nothing pins while the prompt's row is on screen (the desktop's rule).
        while (promptRowTop(listState) != null) {
            onAllNodesWithTag(ChatTimelineTags.PINNED_PROMPT).fetchSemanticsNodes().let {
                assertTrue(it.isEmpty(), "pinned while the prompt's row is on screen")
            }
            runOnIdle { listState.dispatchRawDelta(-STEP_PX) }
            waitForIdle()
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ChatTimelineTags.PINNED_PROMPT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** build/chat-surface-snapshots/timeline-sticky-prompt.png: the prompt held under a header band. */
    @Test
    fun snapshotTheStickyPrompt() = runComposeUiTest {
        val listState = LazyListState()
        show(listState, TOP_INSET, chrome = true)
        repeat(SNAPSHOT_STEPS) {
            runOnIdle { listState.dispatchRawDelta(-STEP_PX) }
            waitForIdle()
        }
        assertTrue(pinnedTop() != null, "the prompt is pinned for the snapshot")
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("timeline-sticky-prompt.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    private companion object {
        val TOP_INSET = 96.dp
        const val STEP_PX = 6f
        const val FAR_PX = 10_000f
        const val TOLERANCE_PX = 1.5f
        const val HELD_STEPS = 12
        const val MAX_STEPS = 200
        const val SNAPSHOT_STEPS = 60
        const val CHROME_ALPHA = 0.85f
    }
}
