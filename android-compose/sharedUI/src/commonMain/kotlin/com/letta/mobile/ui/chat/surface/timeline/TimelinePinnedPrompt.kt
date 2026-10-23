package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

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
 * The prompt pinned over the list, on every platform a sticky header: its copy rides its own row
 * until the row reaches the visible top ([stickLinePx] down, below any chrome floating over the
 * list, ChatSurfacePlatform.topChromeInset; the list's own top edge where the header sits above it,
 * as in the shared shell), then holds there, never travelling up under the chrome, and the next
 * prompt coming up pushes it out from below. The row it stands in for is hidden meanwhile. The copy
 * is the prompt's bubble alone, without the row's leading space, so it holds snug at the top.
 * (A pin that waited for the row to leave the screen let the prompt scroll away and snap back.)
 */
@Stable
internal class PinnedPrompt(
    private val listState: LazyListState,
    private val owner: State<PinnedOwner?>,
    /** The list's visible top, in px from its top edge. */
    val stickLinePx: Int,
    /** How the copy's images shrink as it docks. */
    val compaction: DockedCopyCompaction,
    /** The top of the prompt row riding up to the line (not the owner); see [ridingPromptTop]. */
    private val riding: State<Int?> = mutableStateOf(null),
) {
    val item: ChatRenderItem? get() = owner.value?.item

    /**
     * The top of the highest prompt row still riding up to the line, px from the list's top edge;
     * null when none is in view. The list's fades end above it, so a prompt is never shaded while it
     * rides up or pushes the held copy out (read at draw time only).
     */
    val ridingPromptTopPx: Int? get() = riding.value

    /** The row keyed [key] is the one the sticky copy stands in for. */
    fun standsInFor(key: String): Boolean = owner.value?.item?.key == key

    /**
     * The sticky copy's bottom edge as last placed, in px from the list's top edge: the timeline's
     * pinned fade clears the rows down to it (read at draw time only).
     */
    var copyBottomPx by mutableIntStateOf(0)
        private set

    /** The sticky copy's top as last placed (read at draw time only): above [stickLinePx] it is being pushed out. */
    var copyTopPx by mutableIntStateOf(0)
        private set

    /** Where the sticky copy's top goes, in px from the list's top edge, for a copy [heightPx] tall. */
    fun copyTop(heightPx: Int): Int {
        val current = owner.value
        compaction.recordCopyHeight(heightPx)
        val top = if (current == null) stickLinePx else stickyCopyTop(listState.layoutInfo, current, stickLinePx, heightPx)
        copyTopPx = top
        copyBottomPx = top + heightPx
        return top
    }
}

/**
 * The prompt pinned over [listState]'s list. [topReserve] is the chrome floating over its top edge
 * (TimelineFrameOverlays.topReserve), where the sticky copy holds; zero holds it at the list's top edge.
 */
@Composable
internal fun rememberPinnedPrompt(
    listState: LazyListState,
    itemCount: Int,
    topReserve: Dp,
    itemAt: (Int) -> ChatRenderItem?,
): PinnedPrompt {
    val stickLinePx = with(LocalDensity.current) { topReserve.roundToPx() }
    val reducedMotion = LocalReducedMotion.current
    val currentCount = rememberUpdatedState(itemCount)
    val currentItemAt = rememberUpdatedState(itemAt)
    val owner = remember(listState, stickLinePx) {
        derivedStateOf {
            val rows = PromptRows(currentCount.value, currentItemAt.value)
            val info = listState.layoutInfo
            stickyOwner(info, stickLinePx, rows)
        }
    }
    val riding = remember(listState, owner, stickLinePx) {
        derivedStateOf {
            val rows = PromptRows(currentCount.value, currentItemAt.value)
            ridingPromptTop(listState.layoutInfo, stickLinePx, rows, owner.value)
        }
    }
    return remember(listState, owner, stickLinePx, reducedMotion, riding) {
        val motion = DockedCopyMotion.of(reducedMotion = reducedMotion)
        PinnedPrompt(listState, owner, stickLinePx, DockedCopyCompaction(listState, owner, stickLinePx, motion), riding)
    }
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
 * The sticky owner: the first prompt at or above the row at the visible top
 * ([stickLinePx] down), once that prompt's row has reached it. A row's offset counts up from the
 * list's bottom in a reversed list, so its top is [LazyListLayoutInfo.viewportEndOffset] less its
 * offset and size from the top edge; the rows whose top is at or above the line have reached it.
 */
internal fun stickyOwner(info: LazyListLayoutInfo, stickLinePx: Int, rows: PromptRows): PinnedOwner? {
    val line = info.viewportEndOffset - stickLinePx
    val visible = info.visibleItemsInfo
    // At or past: with the line at the list's own top edge (no chrome over it) a row can reach the
    // viewport's end but never pass it, and a strict test then found no owner at all.
    val atLine = visible.filter { it.offset + it.size >= line }.minOfOrNull { it.index } ?: return null
    val index = rows.firstPromptFrom(atLine) ?: return null
    val item = rows.itemAt(index) ?: return null
    val next = visible.filter { it.index < index && rows.isPrompt(it.index) }.maxOfOrNull { it.index }
    return PinnedOwner(index, item, next)
}

/**
 * The top of the highest prompt row at or below the visible top ([stickLinePx] down) other than the
 * sticky [owner]'s, px from the list's top edge: the prompt riding up to the line, or
 * the next prompt pushing the held copy out. Null when no such row is in view.
 */
internal fun ridingPromptTop(info: LazyListLayoutInfo, stickLinePx: Int, rows: PromptRows, owner: PinnedOwner?): Int? {
    val end = info.viewportEndOffset
    return info.visibleItemsInfo
        .filter { it.index != owner?.index && rows.isPrompt(it.index) }
        .map { end - it.offset - it.size }
        .filter { it >= stickLinePx }
        .minOrNull()
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

/**
 * The header shading for the sticky copy. The copy is drawn over the list,
 * outside the list's own top fade, so when the next prompt pushes it up past the visible top it
 * would run hard under the status bar and the header's controls. Instead whatever of it has crossed
 * the line dissolves over [ChatTimelineDimens.stickyPromptExitFadeLength] and is gone above that, so
 * nothing of it reaches the header band's controls. Held at the line, nothing is shaded. Read at draw
 * time only: a scroll redraws the mask, never recomposes or re-lays out the copy.
 */
internal fun Modifier.stickyCopyHeaderFade(pinned: PinnedPrompt): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        // The visible top in the copy's own coordinates: positive once it is pushed above it.
        val line = (pinned.stickLinePx - pinned.copyTopPx).toFloat()
        if (line <= 0f) return@drawWithContent
        val fade = ChatTimelineDimens.stickyPromptExitFadeLength.toPx()
        // Fully gone above `gone`; just pushed (less than the ramp over the line) its top edge is
        // only partly faded, so the ramp never steps.
        val gone = line - fade
        val topEdge = Color.Black.copy(alpha = (-gone / fade).coerceIn(0f, 1f))
        drawRect(
            brush = Brush.verticalGradient(
                0f to topEdge,
                (gone.coerceAtLeast(0f) / line) to topEdge,
                1f to Color.Black,
                startY = 0f,
                endY = line,
            ),
            size = Size(size.width, line),
            blendMode = BlendMode.DstIn,
        )
    }

/** Places the sticky copy where [pinned] says, read at placement time so a scroll never recomposes it. */
internal fun Modifier.stickyCopyPlacement(pinned: PinnedPrompt): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(0, pinned.copyTop(placeable.height))
    }
}
