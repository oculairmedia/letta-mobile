@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.sendflight.SendFlightTestTags
import com.letta.mobile.ui.chat.surface.timeline.RowCompositionCounter
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import java.io.File
import javax.imageio.ImageIO

/** How the page under test is mounted. */
internal data class SendLiftMount(
    val presentation: ChatSurfacePresentation = ChatSurfacePresentation.ChatFirst,
    /** The soft keyboard's height: the page is laid out above it, as the Touch bar's imePadding does. */
    val keyboard: Dp = 0.dp,
    /** The paged timeline the canonical Touch route reads; null mounts the legacy list. */
    val paged: CanonicalTimelinePresentation? = null,
)

/**
 * letta-mobile-86njl.1: mounts the real ChatSurface at 412x915 dp with the Touch platform style on a
 * manual clock and records a [SendFrame] after every 16 ms frame of a send. Reuses the timeline
 * harness's [RowCompositionCounter] and scroll probe rather than counting its own. [pump] runs
 * before each frame: a paged page needs its domain work given real time (see PagedSendLiftRig).
 */
internal class SendLiftFrameRecorder(
    private val test: ComposeUiTest,
    val port: SendLiftPort,
    private val pump: () -> Unit = {},
) : AutoCloseable {
    private val compositions = RowCompositionCounter()
    private val list = SendLiftListView(test) { port.promptKey }
    private val recorded = mutableListOf<SendFrame>()
    private var sentText = ""
    private var clock = BASELINE_T

    val frames: List<SendFrame> get() = recorded.toList()

    fun mount(options: SendLiftMount = SendLiftMount()) = with(test) {
        mainClock.autoAdvance = false
        setContent {
            FakeMascotShell(SendLiftPort.AGENT).Provide {
                MaterialTheme {
                    Box(Modifier.size(width = WIDTH.dp, height = HEIGHT.dp).padding(bottom = options.keyboard)) {
                        ChatSurface(
                            port = port,
                            presentation = options.presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(),
                            appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                            pagedTimeline = options.paged,
                        )
                    }
                }
            }
        }
        step(SETTLE_FRAMES)
    }

    /** Advances [count] frames without recording them. */
    fun step(count: Int) = repeat(count) {
        pump()
        test.mainClock.advanceTimeByFrame()
    }

    /** Scrolls the list one viewport towards older messages and lets it settle. */
    fun scrollListAwayFromTheEdge() {
        list.scrollAwayFromTheEdge()
        step(SETTLE_FRAMES)
    }

    /** True when some node on the page shows [text]. */
    fun textOnScreen(text: String): Boolean = count(hasText(text, substring = true)) > 0

    /** Records the baseline frame, taps send, and records the send frame (t = 0). */
    fun tapSend(prompt: String) {
        sentText = prompt
        clock = BASELINE_T
        record()
        test.onNodeWithTag(ComposerTestTags.SEND).performClick()
        advance(1)
    }

    /** Advances [count] frames of 16 ms, recording each. */
    fun advance(count: Int) = repeat(count) {
        step(1)
        clock += FRAME_MILLIS
        record()
    }

    private fun record() {
        var frame: SendFrame? = null
        test.runOnIdle { frame = observe() }
        recorded += checkNotNull(frame)
    }

    private fun observe(): SendFrame {
        val bar = bounds(hasTestTag(ComposerTestTags.TOUCH_BAR))
        val reading = list.read(bar.top)
        return SendFrame(
            t = clock,
            promptBubble = boundsOrNull(hasTestTag(ChatRowTestTags.USER_PROMPT) and hasText(sentText, substring = true)),
            ghost = boundsOrNull(hasTestTag(SendFlightTestTags.GHOST)),
            promptSlot = reading.promptSlot,
            olderRow = reading.olderRow,
            composerField = bounds(hasTestTag(ComposerTestTags.INPUT) or hasTestTag(ComposerTestTags.DOCKED_INPUT)),
            viewport = reading.viewport,
            companionRowHeight = reading.bottom?.let { (bar.top - it).coerceAtLeast(0f) } ?: 0f,
            firstVisibleItemIndex = reading.firstVisibleItemIndex,
            scrollOffset = reading.scrollOffset,
            scrollCommands = reading.scrollCommands,
            rowCompositions = compositions.takeDelta(),
            chevronCount = count(hasContentDescription(EXPAND) or hasContentDescription(COLLAPSE)),
            departingTextCount = count(hasTestTag(DEPARTING_TEXT_TAG)),
        )
    }

    private fun bounds(matcher: SemanticsMatcher): Rect = test.onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot

    private fun boundsOrNull(matcher: SemanticsMatcher): Rect? =
        test.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun count(matcher: SemanticsMatcher): Int = test.onAllNodes(matcher).fetchSemanticsNodes().size

    /** Writes what the page draws now to build/send-lift-frames/<label>.png, for review. Never committed. */
    fun writeFrameImage(label: String) {
        ImageIO.write(test.onRoot().captureToImage().toAwtImage(), "png", outputDir().resolve("$label.png"))
    }

    /** Writes every recorded frame, one line each, to build/send-lift-frames/<label>.txt. */
    fun writeFrameLog(label: String) {
        outputDir().resolve("$label.txt").writeText(recorded.joinToString(System.lineSeparator()))
    }

    private fun outputDir(): File = File("build/send-lift-frames").apply { mkdirs() }

    override fun close() {
        list.close()
        compositions.close()
    }

    companion object {
        const val WIDTH = 412
        const val HEIGHT = 915
        const val FRAME_MILLIS = 16
        const val BASELINE_T = -FRAME_MILLIS
        private const val SETTLE_FRAMES = 10
        private const val EXPAND = "Expand prompt"
        private const val COLLAPSE = "Collapse prompt"

        /** The tag the redesign gives the departing draft text (letta-mobile-86njl.2). */
        const val DEPARTING_TEXT_TAG = "chat-send-departing-text"
    }
}
