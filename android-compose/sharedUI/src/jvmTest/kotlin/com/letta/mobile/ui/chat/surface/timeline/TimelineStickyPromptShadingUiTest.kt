@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.theme.ChatTimelineDimens
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList

/**
 * The sticky prompt is never shaded on its way up or while it is held (the owner's Pixel report:
 * under the floating header a prompt dimmed before anything pushed it). It rides its row to the
 * stick line and holds there at full colour; the next prompt comes up under it at full colour too,
 * and only the part of the held copy that prompt pushes above the line dissolves.
 */
class TimelineStickyPromptShadingUiTest {
    private val longAnswer = (1..60).joinToString("\n\n") { "Paragraph $it of a long answer." }
    private val messages = listOf(
        UiMessage(id = "q1", role = "user", content = "First question", timestamp = "2026-09-12T12:00:00Z"),
        UiMessage(id = "a1", role = "assistant", content = longAnswer, timestamp = "2026-09-12T12:00:10Z"),
        UiMessage(id = "q2", role = "user", content = "Second question", timestamp = "2026-09-12T12:01:00Z"),
        UiMessage(id = "a2", role = "assistant", content = longAnswer, timestamp = "2026-09-12T12:01:10Z"),
    )

    private fun ComposeUiTest.show(listState: LazyListState) {
        val state = ChatUiState(
            conversationState = ConversationState.Ready("c1"),
            isLoadingMessages = false,
            messages = messages.toPersistentList(),
        )
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
                        topInset = TOP_INSET,
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("Paragraph 60", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The oldest end: the first prompt rests just below the floating chrome.
        runOnIdle { listState.dispatchRawDelta(listState.layoutInfo.totalItemsCount * FAR_PX) }
        waitForIdle()
    }

    /** The bubble of the list row (not the sticky copy) with [text], in root px; null when off screen. */
    private fun ComposeUiTest.rowBubble(text: String): Rect? = onAllNodes(
        hasTestTag(ChatRowTestTags.USER_PROMPT) and hasAnyDescendant(hasText(text)) and
            !hasAnyAncestor(hasTestTag(ChatTimelineTags.PINNED_PROMPT)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The sticky copy's bubble, in root px; null while nothing is pinned. */
    private fun ComposeUiTest.copyBubble(): Rect? = onAllNodes(
        hasTestTag(ChatRowTestTags.USER_PROMPT) and hasAnyAncestor(hasTestTag(ChatTimelineTags.PINNED_PROMPT)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The colour just inside a bubble's top edge, at its middle (above its text). */
    private fun ComposeUiTest.topColour(bubble: Rect): Int {
        val image = onRoot().captureToImage().toAwtImage()
        val inset = with(density) { SAMPLE_INSET.toPx() }
        return image.getRGB(bubble.center.x.toInt(), (bubble.top + inset).toInt())
    }

    /** Scrolls toward the newest by [step] until [done]. */
    private fun ComposeUiTest.scrollUntil(listState: LazyListState, step: Float, done: () -> Boolean) {
        var steps = 0
        while (!done()) {
            check(++steps <= MAX_STEPS) { "never reached the state after $steps steps" }
            runOnIdle { listState.dispatchRawDelta(step) }
            waitForIdle()
        }
    }

    @Test
    fun aStickyPromptIsUnshadedRidingAndHeldAndFadesOnlyWhenPushedAboveTheLine() = runComposeUiTest {
        val listState = LazyListState()
        show(listState)
        val line = with(density) { TOP_INSET.toPx() }
        val fade = with(density) { ChatTimelineDimens.topFadeLength.toPx() }
        // The first prompt as drawn: its sticky copy rides its row (which hides), else the row itself.
        val firstPrompt = { copyBubble() ?: rowBubble("First question") }
        val reference = topColour(checkNotNull(firstPrompt()) { "the first prompt rests in view" })

        // Held at the line, with the rows scrolling (the top fade on) under it.
        scrollUntil(listState, -STEP_PX) { listState.canScrollForward && copyBubble() != null }
        val held = checkNotNull(copyBubble())
        assertTrue(abs(held.top - line) <= TOLERANCE_PX, "the first prompt holds at the line: ${held.top} vs $line")
        assertUnshaded(reference, topColour(held), "held at the line")

        // The next prompt comes up under the held copy, through the copy's fade band.
        scrollUntil(listState, -FAST_STEP_PX) { rowBubble("Second question")?.let { it.top < line + fade + NEAR_PX } == true }
        scrollUntil(listState, -STEP_PX) {
            val copy = checkNotNull(copyBubble())
            rowBubble("Second question")?.let { it.top < copy.bottom + fade / 2 } == true
        }
        assertUnshaded(reference, topColour(checkNotNull(rowBubble("Second question"))), "the next prompt riding up")
        assertUnshaded(reference, topColour(checkNotNull(copyBubble())), "the copy still held")

        // Pushed: what of the copy has crossed the line dissolves.
        val exit = with(density) { ChatTimelineDimens.stickyPromptExitFadeLength.toPx() }
        val sample = with(density) { SAMPLE_INSET.toPx() }
        scrollUntil(listState, -STEP_PX) { copyBubble()?.let { it.top + sample < line - exit / 2 } != false }
        val pushed = checkNotNull(copyBubble()) { "the next prompt took over before the copy was pushed out" }
        assertTrue(!near(reference, topColour(pushed)), "the copy pushed above the line is faded")
        assertUnshaded(reference, topColour(checkNotNull(rowBubble("Second question"))), "the next prompt pushing")
    }

    private fun assertUnshaded(reference: Int, actual: Int, phase: String) {
        assertTrue(near(reference, actual), "$phase: the bubble is shaded (${hex(actual)} vs ${hex(reference)})")
    }

    private fun hex(argb: Int) = "#%08x".format(argb)

    private fun near(argb: Int, other: Int): Boolean {
        fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF
        return listOf(0, 8, 16).all { abs(channel(argb, it) - channel(other, it)) <= PIXEL_TOLERANCE }
    }

    private companion object {
        val TOP_INSET = 96.dp
        val SAMPLE_INSET = 3.dp
        const val STEP_PX = 4f
        const val FAST_STEP_PX = 40f
        const val NEAR_PX = 200f
        const val FAR_PX = 10_000f
        const val TOLERANCE_PX = 1.5f
        const val MAX_STEPS = 600
        const val PIXEL_TOLERANCE = 6
    }
}
