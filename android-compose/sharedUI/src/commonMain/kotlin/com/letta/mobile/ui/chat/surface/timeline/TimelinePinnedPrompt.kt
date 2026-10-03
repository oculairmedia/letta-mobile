package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.chat.projection.ChatRenderItem

/**
 * How far above the viewport the owning prompt is looked for. Bounded because it runs per scroll
 * frame; past it the transcript scrolls with no pinned prompt (desktop PinnedPromptScanLimit).
 */
private const val PINNED_PROMPT_SCAN_LIMIT = 400

/** The prompt that owns what is on screen: its index, the item, and the next prompt below it in view. */
@Immutable
internal data class PinnedOwner(
    val index: Int,
    val item: ChatRenderItem,
    /** The nearest newer prompt on screen under it, which pushes the sticky copy out from below. */
    val nextPromptIndex: Int? = null,
)

/**
 * The prompt pinned over the list (desktop rememberPinnedPrompt), letta-mobile-bglj6.1.
 *
 * With no chrome over the list ([stickLinePx] 0, the desktop) it pins once its row has left the
 * screen, while no prompt is on screen. Under floating chrome (the phone's status bar and header,
 * ChatSurfacePlatform.topChromeInset) the list draws up under the chrome, so the visible top is
 * [stickLinePx] down: there the prompt is a sticky header. Its copy rides its own row until the row
 * reaches the visible top, then holds there, never travelling up under the chrome, and the next
 * prompt coming up pushes it out from below. The row it stands in for is hidden meanwhile. The copy
 * is the prompt's bubble alone, without the row's leading space, so it holds snug under the chrome.
 */
@Stable
internal class PinnedPrompt(
    private val listState: LazyListState,
    private val owner: State<PinnedOwner?>,
    /** The list's visible top, in px from its top edge. */
    val stickLinePx: Int,
) {
    val item: ChatRenderItem? get() = owner.value?.item

    /** The copy is a sticky header that holds at [stickLinePx] (floating chrome over the list). */
    val sticky: Boolean get() = stickLinePx > 0

    /** The row keyed [key] is the one the sticky copy stands in for. */
    fun standsInFor(key: String): Boolean = sticky && owner.value?.item?.key == key

    /**
     * The sticky copy's bottom edge as last placed, in px from the list's top edge: the timeline's
     * pinned fade clears the rows down to it (read at draw time only).
     */
    var copyBottomPx by mutableIntStateOf(0)
        private set

    /** Where the sticky copy's top goes, in px from the list's top edge, for a copy [heightPx] tall. */
    fun copyTop(heightPx: Int): Int {
        val current = owner.value
        val top = if (current == null) stickLinePx else stickyCopyTop(listState.layoutInfo, current, stickLinePx, heightPx)
        copyBottomPx = top + heightPx
        return top
    }
}

/**
 * The prompt pinned over [listState]'s list. [topReserve] is the chrome floating over its top edge
 * (TimelineFrameOverlays.topReserve): zero keeps the desktop's pin, more makes it a sticky header.
 */
@Composable
internal fun rememberPinnedPrompt(
    listState: LazyListState,
    itemCount: Int,
    topReserve: Dp,
    itemAt: (Int) -> ChatRenderItem?,
): PinnedPrompt {
    val stickLinePx = with(LocalDensity.current) { topReserve.roundToPx() }
    val currentCount = rememberUpdatedState(itemCount)
    val currentItemAt = rememberUpdatedState(itemAt)
    val owner = remember(listState, stickLinePx) {
        derivedStateOf {
            val rows = PromptRows(currentCount.value, currentItemAt.value)
            val info = listState.layoutInfo
            if (stickLinePx > 0) stickyOwner(info, stickLinePx, rows) else offScreenOwner(info.visibleItemsInfo, rows)
        }
    }
    return remember(listState, owner, stickLinePx) { PinnedPrompt(listState, owner, stickLinePx) }
}

/** The list's items as the owner search reads them: how many there are, and each one. */
internal class PromptRows(val count: Int, val itemAt: (Int) -> ChatRenderItem?) {
    fun isPrompt(index: Int): Boolean = itemAt(index)?.isUserPrompt() == true

    /** The first prompt at or above [from] (in a reversed list: at or older than it), within the scan limit. */
    fun firstPromptFrom(from: Int): Int? {
        val end = minOf(count, from + PINNED_PROMPT_SCAN_LIMIT)
        return (from until end).firstOrNull(::isPrompt)
    }
}

/**
 * The desktop's owner: in a reversed list the visual top is the HIGHEST visible index, and a
 * prompt's answer (newer) sits at a LOWER index beneath it, so the owner is the first prompt at or
 * above the topmost visible row. Compose's stickyHeader cannot express this: in a reversed list it
 * pins to the bottom. Null while the prompt's own row is on screen, so it is never drawn twice, and
 * null while ANY prompt is on screen: the pinned copy would sit over it (two "You" bubbles
 * overlapping), and a prompt in view already says what the rows below it answer.
 */
internal fun offScreenOwner(visible: List<LazyListItemInfo>, rows: PromptRows): PinnedOwner? {
    val top = visible.maxOfOrNull { it.index } ?: return null
    if (visible.any { rows.isPrompt(it.index) }) return null
    val index = rows.firstPromptFrom(top) ?: return null
    return rows.itemAt(index)?.let { PinnedOwner(index, it) }
}

/**
 * The sticky owner under floating chrome: the first prompt at or above the row at the visible top
 * ([stickLinePx] down), once that prompt's row has reached it. A row's offset counts up from the
 * list's bottom in a reversed list, so its top is [LazyListLayoutInfo.viewportEndOffset] less its
 * offset and size from the top edge; the rows whose top is above the line have crossed it.
 */
internal fun stickyOwner(info: LazyListLayoutInfo, stickLinePx: Int, rows: PromptRows): PinnedOwner? {
    val line = info.viewportEndOffset - stickLinePx
    val visible = info.visibleItemsInfo
    val atLine = visible.filter { it.offset + it.size > line }.minOfOrNull { it.index } ?: return null
    val index = rows.firstPromptFrom(atLine) ?: return null
    val item = rows.itemAt(index) ?: return null
    val next = visible.filter { it.index < index && rows.isPrompt(it.index) }.maxOfOrNull { it.index }
    return PinnedOwner(index, item, next)
}

/**
 * The sticky copy's top, from the list's top edge: on its own row while that row is below the
 * visible top (so the hand-off is seamless), then held at [stickLinePx], and pushed up by the next
 * prompt's row when it arrives under the copy.
 */
internal fun stickyCopyTop(info: LazyListLayoutInfo, owner: PinnedOwner, stickLinePx: Int, copyHeightPx: Int): Int {
    val end = info.viewportEndOffset
    val visible = info.visibleItemsInfo
    // Bottom-aligned with its row: the row may carry a day divider above the prompt.
    val onRow = visible.firstOrNull { it.index == owner.index }?.let { end - it.offset - copyHeightPx }
    val held = maxOf(stickLinePx, onRow ?: stickLinePx)
    val next = owner.nextPromptIndex?.let { index -> visible.firstOrNull { it.index == index } } ?: return held
    return minOf(held, end - next.offset - next.size - copyHeightPx)
}

/**
 * The pinned prompt the list's rows are drawn under, so the row the sticky copy stands in for
 * hides; null where there is no sticky copy.
 */
internal val LocalStickyPrompt = staticCompositionLocalOf<PinnedPrompt?> { null }

/** Hides the row keyed [key] while the sticky copy stands in for it (read at draw time only). */
internal fun Modifier.hiddenUnderStickyCopy(pinned: PinnedPrompt?, key: String): Modifier {
    if (pinned == null) return this
    return graphicsLayer { alpha = if (pinned.standsInFor(key)) 0f else 1f }
}

/** Places the sticky copy where [pinned] says, read at placement time so a scroll never recomposes it. */
internal fun Modifier.stickyCopyPlacement(pinned: PinnedPrompt): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(0, pinned.copyTop(placeable.height))
    }
}
