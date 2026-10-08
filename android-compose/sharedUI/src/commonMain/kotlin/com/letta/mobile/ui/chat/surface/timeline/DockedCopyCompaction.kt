package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.ui.chat.surface.timeline.rows.DockedPromptCompaction

/** How a pinned prompt's images shrink as it docks (letta-mobile-bglj6.1.26). */
internal enum class DockedCopyMotion {
    /** The desktop's pinned card: it only shows once the prompt has left, always compact. */
    AlwaysCompact,

    /** A sticky copy: the images shrink one to one with the scroll that docks it. */
    FollowScroll,

    /** A sticky copy under reduced motion: full size on its row, thumbnails once docked. */
    Snap,
    ;

    companion object {
        fun of(sticky: Boolean, reducedMotion: Boolean): DockedCopyMotion = when {
            !sticky -> AlwaysCompact
            reducedMotion -> Snap
            else -> FollowScroll
        }
    }
}

/**
 * How far the pinned copy's images shrink. A sticky copy gives up exactly as much height as its
 * row's bubble has travelled past the visible top, so the copy's bottom edge (and the reply beneath
 * it) follows the scroll one to one until the images are thumbnails, then the copy holds. Back down
 * they grow the same way, full size again by the time the copy rides its row, so the hand-off to the
 * row stays seamless. Everything is read at layout time: a scroll relayouts the copy, never recomposes it.
 */
@Stable
internal class DockedCopyCompaction(
    private val listState: LazyListState,
    private val owner: State<PinnedOwner?>,
    /** The list's visible top, in px from its top edge (PinnedPrompt.stickLinePx). */
    private val stickLinePx: Int,
    private val motion: DockedCopyMotion,
) : DockedPromptCompaction {

    /** The copy's height before its images shrank, for the prompt keyed [naturalKey]. */
    private var naturalPx by mutableIntStateOf(0)
    private var naturalKey: String? = null

    /** What the copy's images gave up when last measured, for the prompt keyed [reportedKey]. */
    private var reportedPx = 0
    private var reportedKey: String? = null

    override fun shrinkPx(): Int {
        if (motion == DockedCopyMotion.AlwaysCompact) return Int.MAX_VALUE
        val travel = dockTravel()
        return when {
            travel <= 0 -> 0
            motion == DockedCopyMotion.Snap -> Int.MAX_VALUE
            else -> travel
        }
    }

    override fun reportShrink(px: Int) {
        reportedKey = owner.value?.item?.key
        reportedPx = px
    }

    /** The copy was placed [heightPx] tall: with what its images gave up, that is its natural height. */
    fun recordCopyHeight(heightPx: Int) {
        val key = owner.value?.item?.key ?: return
        val shrunk = if (reportedKey == key) reportedPx else 0
        naturalKey = key
        naturalPx = heightPx + shrunk
    }

    /**
     * How far the owner's bubble (bottom-aligned with its row) has travelled up past the visible top:
     * zero or less while it is still below, unbounded once its row has left the screen.
     */
    private fun dockTravel(): Int {
        val current = owner.value ?: return 0
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == current.index } ?: return Int.MAX_VALUE
        val natural = if (naturalKey == current.item.key) naturalPx else 0
        return stickLinePx + natural - (info.viewportEndOffset - row.offset)
    }
}
