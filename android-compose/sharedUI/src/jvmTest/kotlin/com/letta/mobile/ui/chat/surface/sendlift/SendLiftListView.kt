@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.chat.surface.timeline.ScrollCommandProbe

/** What the timeline list showed in one frame; the defaults are a page without a list (the docked canvas). */
internal data class ListReading(
    val promptSlot: Rect? = null,
    val olderRow: Rect? = null,
    val viewport: Rect = Rect.Zero,
    val firstVisibleItemIndex: Int = 0,
    val scrollOffset: Int = 0,
    val scrollCommands: Int = 0,
    /** The list's bottom edge in the root, for the gap down to the composer bar. */
    val bottom: Float? = null,
)

/**
 * letta-mobile-86njl.1: reads the page's timeline list. The page keeps its [LazyListState] private,
 * so the state is found through the list's scroll semantics ([ListStateFinder]) the first time it is
 * needed, and every frame after reads the list's layout directly.
 */
internal class SendLiftListView(private val test: ComposeUiTest, private val promptKey: () -> String) : AutoCloseable {
    private var state: LazyListState? = null
    private var probe: ScrollCommandProbe? = null

    /** The list as it is now; [barTop] caps the viewport (the composer bar covers the list's foot). */
    fun read(barTop: Float): ListReading {
        val list = stateOrNull() ?: return ListReading()
        val bounds = test.onAllNodes(hasTestTag(ChatTimelineTags.LIST)).fetchSemanticsNodes().first().boundsInRoot
        val geometry = Geometry(bounds, list.layoutInfo.viewportEndOffset)
        val items = list.layoutInfo.visibleItemsInfo
        return ListReading(
            promptSlot = items.firstOrNull { it.key == promptKey() }?.let(geometry::rectOf),
            olderRow = items.firstOrNull { it.key.toString().startsWith(ROW_KEY_PREFIX) && it.key != promptKey() }?.let(geometry::rectOf),
            viewport = bounds.copy(bottom = minOf(bounds.bottom, barTop)),
            firstVisibleItemIndex = list.firstVisibleItemIndex,
            scrollOffset = list.firstVisibleItemScrollOffset,
            scrollCommands = probeOf(list).takeDelta(),
            bottom = bounds.bottom,
        )
    }

    /** Scrolls the list towards older messages, as a person reading back would. */
    fun scrollAwayFromTheEdge() {
        checkNotNull(stateOrNull()) { "the page has no timeline list to scroll" }
        repeat(SWIPES) {
            test.onNodeWithTag(ChatTimelineTags.LIST).performTouchInput { swipeDown(startY = top + SWIPE_FROM_PX, endY = top + SWIPE_TO_PX, durationMillis = SWIPE_MILLIS) }
            repeat(SWIPE_SETTLE_FRAMES) { test.mainClock.advanceTimeByFrame() }
        }
    }

    /** Where a list item sits in the root: the list is reversed, so offsets run up from the viewport's end. */
    private class Geometry(private val list: Rect, private val viewportEnd: Int) {
        fun rectOf(item: LazyListItemInfo): Rect {
            val top = list.top + viewportEnd - item.offset - item.size
            return Rect(list.left, top, list.right, top + item.size)
        }
    }

    private fun probeOf(list: LazyListState): ScrollCommandProbe = probe ?: ScrollCommandProbe(list).also { probe = it }

    private fun stateOrNull(): LazyListState? = state ?: findState()?.also { state = it }

    private fun findState(): LazyListState? {
        val node = test.onAllNodes(hasTestTag(ChatTimelineTags.LIST)).fetchSemanticsNodes().firstOrNull() ?: return null
        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
        return checkNotNull(ListStateFinder.within(range.value)) { "no LazyListState reachable from the list's scroll semantics" }
    }

    override fun close() {
        probe?.close()
    }

    private companion object {
        const val ROW_KEY_PREFIX = "msg-"
        const val SWIPES = 2
        const val SWIPE_FROM_PX = 100f
        const val SWIPE_TO_PX = 600f
        const val SWIPE_MILLIS = 200L
        const val SWIPE_SETTLE_FRAMES = 12
    }
}
