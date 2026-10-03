@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import java.io.File
import javax.imageio.ImageIO

/**
 * letta-mobile-29sxj: mounts the shared ChatTimeline over a [TimelineDomainRig]'s presentation and
 * steps it one Compose frame at a time, recording a [UiFrame] after each: row bounds and keys, the
 * spinner, the scroll position and how much row content was composed.
 *
 * The clock is manual, so a frame is exactly what the list would draw at that tick. Domain work
 * runs on real dispatchers, so [advance] gives it real time between frames.
 */
internal class TimelineUiFrameRecorder(
    private val test: ComposeUiTest,
    private val rig: TimelineDomainRig,
    private val main: ManualMainDispatcher,
) : AutoCloseable {
    private val compositions = RowCompositionCounter()
    private val recorded = mutableListOf<UiFrame>()
    private var uiDensity = Density(1f)
    private lateinit var listState: LazyListState
    private var scrollProbe: ScrollCommandProbe? = null
    private var state by mutableStateOf(ChatUiState(conversationState = ConversationState.Ready(CONVERSATION), isLoadingMessages = false))

    val frames: List<UiFrame> get() = recorded.toList()

    fun mount() = with(test) {
        mainClock.autoAdvance = false
        setContent {
            listState = rememberLazyListState()
            uiDensity = LocalDensity.current
            MaterialTheme {
                Box(Modifier.size(width = WIDTH.dp, height = HEIGHT.dp)) {
                    ChatTimeline(
                        state = state,
                        pagedTimeline = rig.presentation,
                        actions = RecordingChatActions(),
                        capabilities = ChatSurfaceCapabilities.Default,
                        host = ChatSurfaceHost(),
                        appearance = ChatSurfaceAppearance(),
                        listState = listState,
                    )
                }
            }
        }
    }

    /** Advances [count] frames of [step], letting the domain side run in real time between them. */
    fun advance(step: String, count: Int = FRAMES_PER_STEP) {
        repeat(count) {
            Thread.sleep(REAL_MILLIS_PER_FRAME)
            main.drain()
            test.mainClock.advanceTimeBy(FRAME_MILLIS)
            record(step)
        }
    }

    private fun record(step: String) {
        var frame: UiFrame? = null
        test.runOnIdle { frame = observe(step) }
        recorded += checkNotNull(frame)
    }

    private fun observe(step: String): UiFrame {
        val info = listState.layoutInfo
        val rows = info.visibleItemsInfo.map { item ->
            val top = if (info.reverseLayout) info.viewportEndOffset - item.offset - item.size else item.offset
            RowBounds(item.key.toString(), top / uiDensity.density, item.size / uiDensity.density)
        }
        return UiFrame(
            index = recorded.size,
            step = step,
            rows = rows.sortedBy { it.top },
            spinnerVisible = spinnerVisible(rows),
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            scrollOffset = listState.firstVisibleItemScrollOffset,
            scrollCommands = scrollProbeOf(listState).takeDelta(),
            rowCompositions = compositions.takeDelta(),
        )
    }

    private fun scrollProbeOf(list: LazyListState): ScrollCommandProbe =
        scrollProbe ?: ScrollCommandProbe(list).also { scrollProbe = it }

    private fun spinnerVisible(rows: List<RowBounds>): Boolean =
        rows.any { it.key == FOOTER_LOADING_KEY } || SPINNER_TAGS.any { tag -> test.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Writes what the list draws now to build/chat-surface-frames/<label>.png, for review. Never committed. */
    fun writeFrameImage(label: String) {
        val dir = File("build/chat-surface-frames").apply { mkdirs() }
        ImageIO.write(test.onRoot().captureToImage().toAwtImage(), "png", dir.resolve("$label.png"))
    }

    /** Writes every recorded frame, one line each, to build/chat-surface-frames/<label>.txt. */
    fun writeFrameLog(label: String) {
        val dir = File("build/chat-surface-frames").apply { mkdirs() }
        dir.resolve("$label.txt").writeText(recorded.joinToString(System.lineSeparator()))
    }

    override fun close() {
        scrollProbe?.close()
        compositions.close()
    }

    companion object {
        const val CONVERSATION = "conv-frames"
        const val WIDTH = 420
        const val HEIGHT = 720
        const val FRAME_MILLIS = 16L
        const val FRAMES_PER_STEP = 6
        private const val REAL_MILLIS_PER_FRAME = 12L
        private const val FOOTER_LOADING_KEY = "canonical-loading"
        private val SPINNER_TAGS = listOf(ChatTimelineTags.SKELETON, TimelineMascotTags.LOADING)
    }
}
