@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList

/**
 * letta-mobile-bglj6.1: a prompt docked at the top of the phone's timeline shrinks its attached
 * image to a thumbnail, so it never eats the reply's room. The shrink follows the scroll one to one
 * (no jump, no animation to wait for), the prompt's row in the list keeps its full-size image, the
 * image grows back as the prompt scrolls home, and under reduced motion it snaps straight to the
 * thumbnail. The thumbnail still opens the viewer.
 */
class TimelineDockedPromptImageUiTest {
    private val longAnswer = (1..120).joinToString("\n\n") { "Paragraph $it of a long answer." }
    private val secondAnswer = (1..40).joinToString("\n\n") { "Line $it of the second answer." }
    private val image =UiImageAttachment(base64 = "", mediaType = "image/png")
    private val messages = listOf(
        UiMessage(
            id = "q1",
            role = "user",
            content = "awesome but I am now seeing this on my elements client",
            timestamp = "2026-09-12T12:00:00Z",
            attachments = listOf(image),
        ),
        UiMessage(id = "a1", role = "assistant", content = longAnswer, timestamp = "2026-09-12T12:00:10Z"),
        UiMessage(id = "q2", role = "user", content = "Second question", timestamp = "2026-09-12T12:01:00Z"),
        // Long enough that the second prompt can scroll up and push the first one out.
        UiMessage(id = "a2", role = "assistant", content = secondAnswer, timestamp = "2026-09-12T12:01:10Z"),
    )
    private val state = ChatUiState(
        conversationState = ConversationState.Ready("c1"),
        isLoadingMessages = false,
        messages = messages.toPersistentList(),
    )

    private fun ComposeUiTest.show(listState: LazyListState, reducedMotion: Boolean = false) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatPlatformStyle provides ChatPlatformStyle.Touch,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    Box(Modifier.size(width = 420.dp, height = 720.dp).background(MaterialTheme.colorScheme.background)) {
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
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("Line 40", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Start at the oldest end: the first prompt rests in view, below the chrome, undocked. The
        // markdown lays out off the main thread, so settle there again once the first answer has.
        runOnIdle { listState.dispatchRawDelta(listState.layoutInfo.totalItemsCount * FAR_PX) }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("Paragraph 120", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        runOnIdle { listState.dispatchRawDelta(listState.layoutInfo.totalItemsCount * FAR_PX) }
        waitForIdle()
    }

    private val inPinnedCopy = hasAnyAncestor(hasTestTag(ChatTimelineTags.PINNED_PROMPT))

    /** The pinned copy's image grid, in root px; null while nothing is pinned. */
    private fun ComposeUiTest.pinnedImage(): Rect? = onAllNodes(hasTestTag(ChatRowTestTags.IMAGE_GRID) and inPinnedCopy, useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The prompt row's own image grid in the list (hidden while the copy stands in for it). */
    private fun ComposeUiTest.rowImage(): Rect? = onAllNodes(
        hasTestTag(ChatRowTestTags.IMAGE_GRID) and !inPinnedCopy,
        useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The image height the reader sees: the docked copy's while pinned, else the row's. */
    private fun ComposeUiTest.shownImageHeight(): Float? = (pinnedImage() ?: rowImage())?.height

    private fun ComposeUiTest.px(dp: Dp): Float = with(density) { dp.toPx() }

    private fun ComposeUiTest.step(deltaPx: Float) {
        runOnIdle { listState.dispatchRawDelta(deltaPx) }
        waitForIdle()
    }

    private lateinit var listState: LazyListState

    /** Scrolls toward the newest, recording the shown image height per frame, until it holds at the cap. */
    private fun ComposeUiTest.dockFully(): List<Float> {
        val cap = px(ChatRowDimens.dockedPromptImageMaxHeight)
        val heights = mutableListOf<Float>()
        var held = 0
        var steps = 0
        while (held < HELD_STEPS && steps < MAX_STEPS) {
            steps++
            step(-STEP_PX)
            val height = shownImageHeight() ?: break
            heights += height
            if (pinnedImage() != null && height <= cap + TOLERANCE_PX) held++
        }
        assertTrue(held >= HELD_STEPS, "the docked image never held at the cap ($steps steps): ${heights.takeLast(5)}")
        return heights
    }

    @Test
    fun theUndockedPromptKeepsItsFullSizeImage() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        val full = px(ChatRowDimens.imageSingleHeight)
        assertTrue(pinnedImage() == null, "nothing is docked at rest")
        val height = rowImage()?.height ?: error("the prompt's image is not on screen")
        assertTrue(abs(height - full) <= TOLERANCE_PX, "the undocked image is full size: $height vs $full")
    }

    @Test
    fun theDockedImageShrinksToTheCapWithoutAJump() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        val full = px(ChatRowDimens.imageSingleHeight)
        val cap = px(ChatRowDimens.dockedPromptImageMaxHeight)
        val heights = listOf(full) + dockFully()
        heights.zipWithNext().forEachIndexed { index, (last, next) ->
            assertTrue(next <= last + TOLERANCE_PX, "the image grew while docking: $last -> $next at frame $index")
            assertTrue(last - next <= STEP_PX + TOLERANCE_PX, "the image jumped: $last -> $next at frame $index")
        }
        // It really travelled through the in-between sizes rather than switching.
        assertTrue(heights.count { it < full - TOLERANCE_PX && it > cap + TOLERANCE_PX } > 2, "no in-between sizes: $heights")
        val docked = pinnedImage() ?: error("nothing docked")
        assertTrue(docked.height <= cap + TOLERANCE_PX, "the docked image is within the cap: ${docked.height} vs $cap")
        // Uniformly scaled: the thumbnail keeps the cell's aspect ratio, so it is narrow too.
        val row = rowImage() ?: error("the prompt's row left the screen")
        // (The row's bounds are clipped to the viewport at its top; its width is whole.)
        assertTrue(abs(docked.width / docked.height - row.width / full) < ASPECT_TOLERANCE, "aspect kept: $docked vs $row x $full")
    }

    @Test
    fun theImageGrowsBackAsThePromptScrollsHome() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        val full = px(ChatRowDimens.imageSingleHeight)
        dockFully()
        var previous = shownImageHeight() ?: error("nothing shown")
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            step(STEP_PX)
            val height = shownImageHeight() ?: error("the prompt vanished")
            assertTrue(height >= previous - TOLERANCE_PX, "the image shrank while undocking: $previous -> $height")
            assertTrue(height - previous <= STEP_PX + TOLERANCE_PX, "the image jumped while undocking: $previous -> $height")
            previous = height
            if (abs(height - full) <= TOLERANCE_PX && pinnedImage() == null) break
        }
        assertTrue(abs(previous - full) <= TOLERANCE_PX, "the image is full size again at home: $previous vs $full")
    }

    @Test
    fun underReducedMotionTheDockedImageSnapsToTheCap() = runComposeUiTest {
        listState = LazyListState()
        show(listState, reducedMotion = true)
        val full = px(ChatRowDimens.imageSingleHeight)
        val cap = px(ChatRowDimens.dockedPromptImageMaxHeight)
        var docked = 0
        repeat(MAX_STEPS) {
            step(-STEP_PX)
            val height = shownImageHeight() ?: return@repeat
            val atAnEnd = abs(height - full) <= TOLERANCE_PX || height <= cap + TOLERANCE_PX
            assertTrue(atAnEnd, "an in-between size under reduced motion: $height (full $full, cap $cap)")
            if (pinnedImage() != null && height <= cap + TOLERANCE_PX) docked++
        }
        assertTrue(docked > 0, "the image never docked")
    }

    @Test
    fun tappingTheDockedThumbnailOpensTheViewer() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        dockFully()
        val thumbnail = hasClickAction() and hasAnyAncestor(hasTestTag(ChatRowTestTags.IMAGE_GRID) and inPinnedCopy)
        onAllNodes(thumbnail, useUnmergedTree = true)[0].performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodes(hasContentDescription("Close image viewer"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The pinned copy's bubble, in root px. */
    private fun ComposeUiTest.pinnedBubble(): Rect? = onAllNodes(
        hasTestTag(ChatRowTestTags.USER_PROMPT) and inPinnedCopy,
        useUnmergedTree = true,
    ).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The second prompt's row top, px from the list's top edge; null off screen. */
    private fun ComposeUiTest.nextPromptTop(): Float? = runOnIdle {
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key.toString().contains("q2") }
            ?.let { (info.viewportEndOffset - it.offset - it.size).toFloat() }
    }

    /**
     * The coordinator's Pixel report: the docked prompt, pushed up by the next prompt, ran hard
     * under the status bar and the header's controls. It now dissolves past the visible top: none of
     * its bubble draws in the header band above the exit fade, while it still draws below the line.
     */
    @Test
    fun aPushedOutDockedPromptDissolvesIntoTheHeaderBand() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        // In root px, like the bubble bounds and the capture.
        val listTop = onNodeWithTag(ChatTimelineTags.LIST).fetchSemanticsNode().boundsInRoot.top
        val line = listTop + px(TOP_INSET)
        val gone = line - px(ChatTimelineDimens.stickyPromptExitFadeLength)
        dockFully()
        val held = pinnedBubble() ?: error("nothing docked")
        val bubbleColor = onRoot().captureToImage().toAwtImage()
            .getRGB((held.left + BUBBLE_EDGE_PX).toInt(), (held.top + held.height / 2).toInt())
        pushIntoHeaderBand(gone - PUSHED_PAST_PX)
        val pushed = pinnedBubble() ?: error("the docked prompt left before it was pushed into the header band")
        assertTrue(pushed.top < gone - PUSHED_PAST_PX && pushed.bottom > line, "pushed across the line: $pushed (line $line)")
        val image = onRoot().captureToImage().toAwtImage()
        val bubblePixels = { ys: IntRange ->
            val xs = (pushed.left.toInt() + 1) until (pushed.right.toInt() - 1)
            ys.sumOf { y -> xs.count { x -> near(image.getRGB(x, y), bubbleColor) } }
        }
        val inHeader = bubblePixels(maxOf(0, pushed.top.toInt()) until gone.toInt())
        assertTrue(inHeader == 0, "$inHeader px of the docked bubble drew in the header band above y=$gone")
        assertTrue(bubblePixels((line.toInt() + 1) until pushed.bottom.toInt()) > 0, "the docked bubble still draws below the line")
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("timeline-docked-prompt-pushed.png")
        ImageIO.write(image, "png", out)
    }

    /**
     * Scrolls fast until the next prompt nears the docked copy, then finely until it has pushed the
     * copy's top above [targetTop] (root px).
     */
    private fun ComposeUiTest.pushIntoHeaderBand(targetTop: Float) {
        var steps = 0
        while ((nextPromptTop() ?: Float.MAX_VALUE) > (pinnedBubble()?.bottom ?: 0f) + NEAR_PX && steps++ < MAX_FAST_STEPS) {
            step(-FAST_STEP_PX)
        }
        while ((pinnedBubble()?.top ?: Float.MAX_VALUE) > targetTop && steps++ < MAX_FAST_STEPS) {
            step(-STEP_PX)
        }
    }

    private fun near(argb: Int, other: Int): Boolean {
        fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF
        return listOf(0, 8, 16).all { abs(channel(argb, it) - channel(other, it)) <= PIXEL_TOLERANCE }
    }

    /** build/chat-surface-snapshots/timeline-docked-prompt-image.png: the prompt docked with a thumbnail. */
    @Test
    fun snapshotTheDockedPrompt() = runComposeUiTest {
        listState = LazyListState()
        show(listState)
        dockFully()
        repeat(SNAPSHOT_EXTRA_STEPS) { step(-STEP_PX) }
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("timeline-docked-prompt-image.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    private companion object {
        const val SNAPSHOT_EXTRA_STEPS = 20
        const val BUBBLE_EDGE_PX = 4f
        const val NEAR_PX = 60f
        const val FAST_STEP_PX = 30f
        const val MAX_FAST_STEPS = 1_000
        const val PUSHED_PAST_PX = 16f
        const val PIXEL_TOLERANCE = 6
        val TOP_INSET = 96.dp
        const val STEP_PX = 6f
        const val FAR_PX = 10_000f
        const val TOLERANCE_PX = 1.5f
        const val ASPECT_TOLERANCE = 0.05f
        const val HELD_STEPS = 8
        const val MAX_STEPS = 200
    }
}
