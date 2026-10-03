@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The scroll-to-latest glide: eased, snapped short, sprung back, instant under reduced motion. */
class TimelineScrollToLatestUiTest {

    private val messages = (0 until 160).map { i ->
        UiMessage(
            id = "m$i",
            role = if (i % 2 == 0) "user" else "assistant",
            content = "message $i",
            timestamp = "2026-09-12T12:%02d:%02dZ".format(i / 60, i % 60),
        )
    }

    private val rowCount: Int = timelineRowsNewestFirst(
        IncrementalChatRenderItemsCache().renderItems(messages, ChatDisplayMode.Interactive, ChatMessageListChange.Full, null),
    ).size

    private fun ComposeUiTest.showFarUp(reducedMotion: Boolean): LazyListState {
        lateinit var listState: LazyListState
        setContent {
            listState = rememberLazyListState()
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                MaterialTheme {
                    Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                        ChatTimeline(
                            state = ChatUiState(
                                conversationState = ConversationState.Ready("c1"),
                                isLoadingMessages = false,
                                messages = messages.toPersistentList(),
                            ),
                            pagedTimeline = null,
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
        // The oldest row: as far from the newest edge as the history goes.
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(rowCount - 1)
        waitForIdle()
        return listState
    }

    private fun ComposeUiTest.newestRowTop(): Float? =
        onAllNodesWithText(messages.last().content).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    private fun LazyListState.atNewestEdge(): Boolean =
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 && !canScrollBackward

    @Test
    fun aTapFromFarUpGlidesShortThenLandsExactlyOnTheNewestEdge() = runComposeUiTest {
        val listState = showFarUp(reducedMotion = false)
        mainClock.autoAdvance = false
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertFalse(listState.atNewestEdge(), "the tap animates rather than jumping")
            // Snapped most of the way: what is left is about a viewport, not the whole history.
            val visible = listState.layoutInfo.visibleItemsInfo.size
            assertTrue(listState.firstVisibleItemIndex <= visible + 1, "glide started at row ${listState.firstVisibleItemIndex}")
        }
        // Where the newest row is drawn, frame by frame (it lays out only once the glide nears it).
        val newestTops = List(GLIDE_FRAMES) {
            mainClock.advanceTimeByFrame()
            newestRowTop()
        }.filterNotNull()
        mainClock.autoAdvance = true
        waitForIdle()
        runOnIdle { assertTrue(listState.atNewestEdge(), "landed at ${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}") }
        val restingTop = assertNotNull(newestRowTop())
        assertTrue(newestTops.min() < restingTop - 1f, "the rows spring a little past the newest edge, then settle back")
        assertEquals(restingTop, newestTops.last(), "the springback is over within the glide")
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertDoesNotExist()
    }

    @Test
    fun underReducedMotionATapSnapsToTheNewestEdgeInOneFrame() = runComposeUiTest {
        val listState = showFarUp(reducedMotion = true)
        mainClock.autoAdvance = false
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).performClick()
        mainClock.advanceTimeByFrame()
        runOnIdle { assertTrue(listState.atNewestEdge()) }
    }

    private companion object {
        /** About a second of frames: the glide and its springback are well inside it. */
        const val GLIDE_FRAMES = 60
    }
}
