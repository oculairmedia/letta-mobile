@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: the paged list's follow. Only the reader's scrolling detaches it; the
 * list's own scrolls (the send's glide to the new prompt) never do, even while Paging has not
 * confirmed the newest edge yet, as on a new chat's first send.
 */
class PagedTimelineFollowTest {

    /** Newest first, as the reversed list holds them. */
    private data class Rows(val keys: List<String>, val newestIsPrompt: Boolean)

    private fun history(count: Int) = Rows((0 until count).map { "row-$it" }, newestIsPrompt = false)

    private fun androidx.compose.ui.test.ComposeUiTest.mount(
        rows: () -> Rows,
        newerHistoryComplete: () -> Boolean,
    ): Pair<() -> PagedFollow, () -> LazyListState> {
        lateinit var follow: PagedFollow
        lateinit var listState: LazyListState
        setContent {
            listState = rememberLazyListState()
            val current = rows()
            follow = rememberPagedFollow(
                "conversation",
                PagedFollowInputs(
                    listState = listState,
                    restoring = false,
                    newerHistoryComplete = newerHistoryComplete(),
                    newestKey = current.keys.firstOrNull(),
                    newestIsUserPrompt = current.newestIsPrompt,
                    identity = current,
                ),
            )
            Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.testTag(LIST)) {
                    items(count = current.keys.size, key = { current.keys[it] }) {
                        Box(Modifier.fillMaxWidth().height(ROW_DP.dp))
                    }
                }
            }
        }
        waitForIdle()
        return { follow } to { listState }
    }

    @Test
    fun theSendsOwnGlideKeepsTheFollowWhilePagingIsMidRefresh() = runComposeUiTest {
        var rows by mutableStateOf(history(ROWS))
        val (follow, listState) = mount(rows = { rows }, newerHistoryComplete = { false })
        assertTrue(follow().following, "a list that opens on its newest edge follows it")

        rows = Rows(listOf("prompt") + rows.keys, newestIsPrompt = true)
        waitForIdle()

        assertTrue(follow().following, "the send's own glide detached the follow it had just re-armed")
        assertTrue(listState().isAtNewestEdge(), "the send lands on the newest edge")
    }

    /** The reply lands at the head while the send's glide is still on its way: the list still ends on the edge. */
    @Test
    fun aReplyArrivingMidGlideStillLandsOnTheNewestEdge() = runComposeUiTest {
        var rows by mutableStateOf(history(ROWS))
        val (follow, listState) = mount(rows = { rows }, newerHistoryComplete = { true })
        onNodeWithTag(LIST).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, DRAG_STEP_PX)) }
            up()
        }
        waitForIdle()
        assertFalse(listState().isAtNewestEdge(), "the reader starts scrolled up")

        mainClock.autoAdvance = false
        rows = Rows(listOf("prompt") + rows.keys, newestIsPrompt = true)
        repeat(MID_GLIDE_FRAMES) { mainClock.advanceTimeByFrame() }
        rows = Rows(listOf("reply") + rows.keys, newestIsPrompt = false)
        mainClock.autoAdvance = true
        waitForIdle()

        assertTrue(follow().following, "the send re-armed the follow")
        assertTrue(listState().isAtNewestEdge(), "ended at ${listState().firstVisibleItemIndex}/${listState().firstVisibleItemScrollOffset}")
    }

    @Test
    fun theReaderScrollingAwayStillDetachesTheFollow()= runComposeUiTest {
        val (follow, _) = mount(rows = { history(ROWS) }, newerHistoryComplete = { true })
        assertTrue(follow().following)

        // Reversed list: dragging down reads older rows.
        onNodeWithTag(LIST).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, DRAG_STEP_PX)) }
            up()
        }
        waitForIdle()

        assertFalse(follow().following, "the reader left the newest edge")
    }

    private companion object {
        const val LIST = "follow-list"
        const val ROWS = 40
        const val ROW_DP = 80
        const val DRAG_STEPS = 6
        const val DRAG_STEP_PX = 40f
        const val MID_GLIDE_FRAMES = 3
    }
}
