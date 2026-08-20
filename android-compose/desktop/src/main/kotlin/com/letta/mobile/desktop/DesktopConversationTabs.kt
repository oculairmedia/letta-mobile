package com.letta.mobile.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
internal data class DesktopConversationTab(
    val conversationId: String,
    val title: String,
    val agentName: String,
)

/** Horizontal gap between tabs, shared by the layout [Arrangement] and the
 * drag-shift math below so the two stay in sync. */
private val TabSpacing = 4.dp

/** How long the "make room for the dragged tab" shift and the post-drop
 * settle-into-place animation run. Shared so the settle animation has a
 * known duration: [DesktopConversationTabRow] clears the drag state once it
 * completes, which is also how long the parent has to commit the reorder
 * (see the overlay doc below) before the hand-off from the floating overlay
 * back to the row's own layout would be visible. */
private const val TabReorderAnimationMillis = 150

/** Left edge and width of a laid-out tab, in px, relative to the tab row's
 * own (unscrolled) content coordinates. Captured from `onGloballyPositioned`
 * so the drag math always reflects the order actually on screen rather than
 * an assumed uniform tab width. */
private data class TabBoundsPx(val left: Float, val width: Float)

/**
 * In-progress (or just-dropped, still settling) reorder gesture: which tab,
 * the index it started at, and its current horizontal offset in px relative
 * to its pre-drag position. While a pointer is actually down, [deltaPx]
 * tracks the live cumulative pointer movement. Once dropped, [settling]
 * flips true and [deltaPx] is overwritten once with the analytically
 * computed resting offset (see [restingDragDeltaPx]) so the floating
 * overlay animates straight there instead of snapping to zero and waiting
 * for the reordered list to come back around — see the overlay doc below
 * for why that hand-off, not the reducer, was the actual "reorder doesn't
 * stick" bug.
 */
private data class TabDragState(
    val conversationId: String,
    val startIndex: Int,
    val deltaPx: Float,
    val settling: Boolean = false,
)

@Composable
internal fun DesktopConversationTabRow(
    tabs: List<DesktopConversationTab>,
    activeConversationId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReorder: (conversationId: String, targetIndex: Int) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { TabSpacing.toPx() }
    // Bounds are keyed by conversationId and refreshed every layout pass, so
    // they stay valid across recompositions without needing to be reset when
    // the tab list itself is reordered.
    val bounds = remember { mutableStateMapOf<String, TabBoundsPx>() }
    var dragState by remember { mutableStateOf<TabDragState?>(null) }
    val scrollState = rememberScrollState()
    val currentDragState = dragState
    val targetIndex = currentDragState?.let { computeDragTargetIndex(tabs, bounds, it) }

    fun beginDrag(tab: DesktopConversationTab, index: Int) {
        dragState = TabDragState(conversationId = tab.conversationId, startIndex = index, deltaPx = 0f)
    }

    fun updateDrag(deltaPx: Float) {
        dragState = dragState?.copy(deltaPx = dragState!!.deltaPx + deltaPx)
    }

    fun endDrag() {
        val drag = dragState ?: return
        val finalTarget = computeDragTargetIndex(tabs, bounds, drag)
        if (finalTarget != drag.startIndex) {
            onReorder(drag.conversationId, finalTarget)
        }
        val restingDeltaPx = restingDragDeltaPx(tabs, bounds, drag, finalTarget, spacingPx)
        dragState = restingDeltaPx?.let { drag.copy(deltaPx = it, settling = true) }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(TabSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                DesktopConversationTabRowSlot(
                    tab = tab,
                    index = index,
                    active = tab.conversationId == activeConversationId,
                    dragState = currentDragState,
                    targetIndex = targetIndex,
                    bounds = bounds,
                    spacingPx = spacingPx,
                    onSelect = onSelect,
                    onClose = onClose,
                    onBoundsChanged = { left, width -> bounds[tab.conversationId] = TabBoundsPx(left, width) },
                    onDragStart = { beginDrag(tab, index) },
                    onDrag = ::updateDrag,
                    onDragStop = ::endDrag,
                )
            }
        }

        DraggedTabOverlay(
            tabs = tabs,
            dragState = currentDragState,
            bounds = bounds,
            scrollOffsetPx = scrollState.value.toFloat(),
            activeConversationId = activeConversationId,
            onClose = onClose,
            onSettled = { dragState = null },
        )
    }
}

/**
 * One tab's slot inside the Row: computes the "make room for the dragged
 * tab" shift animation for a non-dragged neighbor, and renders the tab item
 * itself — invisible (but still laid out, still bounds-tracked, still
 * gesture-live) while it's the one being dragged, since [DraggedTabOverlay]
 * is what actually draws it in that state. Split out from
 * [DesktopConversationTabRow] to keep that composable's own body short.
 */
@Composable
private fun DesktopConversationTabRowSlot(
    tab: DesktopConversationTab,
    index: Int,
    active: Boolean,
    dragState: TabDragState?,
    targetIndex: Int?,
    bounds: Map<String, TabBoundsPx>,
    spacingPx: Float,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onBoundsChanged: (left: Float, width: Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStop: () -> Unit,
) {
    val isDragging = dragState?.conversationId == tab.conversationId
    val shiftPx by animateFloatAsState(
        targetValue = if (dragState != null && targetIndex != null && !isDragging) {
            dragShiftPx(index, dragState, targetIndex, bounds, spacingPx)
        } else {
            0f
        },
        animationSpec = tween(durationMillis = TabReorderAnimationMillis),
        label = "conversationTabReorderShift",
    )
    DesktopConversationTabItem(
        tab = tab,
        active = active,
        dragging = false,
        visible = !isDragging,
        renderOffsetPx = if (isDragging) 0f else shiftPx,
        onSelect = { onSelect(tab.conversationId) },
        onClose = { onClose(tab.conversationId) },
        onBoundsChanged = onBoundsChanged,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragStop = onDragStop,
    )
}

/**
 * Renders the actively-dragged (or just-dropped, still settling) tab as a
 * floating overlay above the row, positioned by absolute offset rather than
 * translated in place inside the Row. The Row's own copy of the same tab
 * ([DesktopConversationTabRowSlot] with `visible = false`) stays mounted so
 * it keeps its layout slot, bounds tracking, and pointer input gesture
 * alive; this overlay is purely a visual mirror driven by [dragState] with
 * no gesture handling of its own.
 *
 * Overlaying, instead of translating the tab in place, is what fixes the
 * clipping bug: `Modifier.horizontalScroll` clips its content to the row's
 * own content bounds, and that clip applies to anything drawn inside it no
 * matter how far a child's own `graphicsLayer` translates it — so once the
 * dragged tab moved roughly a neighbor's width past where the row's content
 * used to end, it got cut off. Something drawn in a plain sibling `Box`,
 * outside the scrolled subtree, is never subject to that clip. The
 * trade-off: the dragged tab now also visually escapes the row's own edges
 * (nothing clips it against the title bar's available width either), and it
 * needs its own explicit correction for the row's current scroll offset
 * ([scrollOffsetPx]) rather than inheriting one for free the way an in-row
 * child would.
 *
 * It also fixes the "reorder doesn't stick" bug, which turned out to be
 * this same draw-only-transform mechanism, not the reducer or the state
 * wiring up to it (both are covered by
 * [DesktopConversationTabsReorderPersistenceTest]/[DesktopConversationTabsHopTest]
 * and hold up under a real driven drag). Dropping a tab used to null out the
 * drag state immediately, which snapped the dragged tab's translateX back
 * to zero — its *original* slot's position — a full frame or more before
 * the reordered list came back down through the app's state (that hand-off
 * goes through a `SideEffect` into a separate composable's `remember`ed
 * var; see `LettaDesktopApp.kt`'s `onHeaderChromeChange` and `Main.kt`'s
 * `headerChrome`). That snap-back was visible as a revert whenever the new
 * order hadn't landed yet. Now [DesktopConversationTabRow.endDrag] instead
 * moves [dragState] into `settling = true` with the analytically-computed
 * resting offset ([restingDragDeltaPx]) as its new `deltaPx`, so this
 * overlay animates smoothly from wherever it was to where the tab will
 * actually end up, and only calls [onSettled] (clearing the drag state,
 * handing back to the row's own now-reordered layout) once that animation
 * finishes — by which point the state hand-off above has had a full
 * [TabReorderAnimationMillis] to land.
 */
@Composable
private fun DraggedTabOverlay(
    tabs: List<DesktopConversationTab>,
    dragState: TabDragState?,
    bounds: Map<String, TabBoundsPx>,
    scrollOffsetPx: Float,
    activeConversationId: String?,
    onClose: (String) -> Unit,
    onSettled: () -> Unit,
) {
    if (dragState == null) return
    val draggedTab = tabs.firstOrNull { it.conversationId == dragState.conversationId } ?: return
    val originLeftPx = bounds[dragState.conversationId]?.left ?: return
    val liveDeltaPx by animateFloatAsState(
        targetValue = dragState.deltaPx,
        animationSpec = if (dragState.settling) tween(TabReorderAnimationMillis) else snap(),
        label = "conversationTabDragOverlayOffset",
        finishedListener = { if (dragState.settling) onSettled() },
    )
    val overlayXPx = originLeftPx - scrollOffsetPx + liveDeltaPx
    DesktopConversationTabItem(
        tab = draggedTab,
        active = draggedTab.conversationId == activeConversationId,
        dragging = true,
        visible = true,
        renderOffsetPx = 0f,
        // No explicit zIndex needed: this overlay is the Box's second child
        // (after the Row), so it already draws on top by declaration order.
        modifier = Modifier.offset { IntOffset(x = overlayXPx.roundToInt(), y = 0) },
        onSelect = {},
        onClose = { onClose(draggedTab.conversationId) },
        onBoundsChanged = { _, _ -> },
        onDragStart = {},
        onDrag = {},
        onDragStop = {},
    )
}

/**
 * Where a tab dragged by [drag] currently belongs among [tabs], expressed as
 * an index into the *pre-drag* order. Compares the dragged tab's live center
 * (its last laid-out center plus the accumulated pointer delta) against the
 * still-tabs' laid-out centers: it has crossed a neighbor once its center
 * passes that neighbor's center. Pure and side-effect free so it can be unit
 * tested without a Compose UI test harness.
 */
private fun computeDragTargetIndex(
    tabs: List<DesktopConversationTab>,
    bounds: Map<String, TabBoundsPx>,
    drag: TabDragState,
): Int {
    val draggedBounds = bounds[drag.conversationId] ?: return drag.startIndex
    val draggedCenter = draggedBounds.left + draggedBounds.width / 2f + drag.deltaPx
    var target = drag.startIndex
    tabs.forEachIndexed { index, tab ->
        if (tab.conversationId == drag.conversationId) return@forEachIndexed
        val neighborBounds = bounds[tab.conversationId] ?: return@forEachIndexed
        val neighborCenter = neighborBounds.left + neighborBounds.width / 2f
        when {
            index < drag.startIndex && draggedCenter < neighborCenter -> target = minOf(target, index)
            index > drag.startIndex && draggedCenter > neighborCenter -> target = maxOf(target, index)
        }
    }
    return target.coerceIn(0, tabs.lastIndex)
}

/**
 * The px offset a non-dragged tab at [index] should animate to so it visibly
 * makes room for the dragged tab landing at [targetIndex] — the "other tabs
 * shift to show where it will land" behavior. Tabs strictly between the
 * drag's start and target position shift by one dragged-tab-width (plus
 * spacing) toward the vacated slot; everything else stays put.
 */
private fun dragShiftPx(
    index: Int,
    drag: TabDragState,
    targetIndex: Int,
    bounds: Map<String, TabBoundsPx>,
    spacingPx: Float,
): Float {
    if (index == drag.startIndex || targetIndex == drag.startIndex) return 0f
    val draggedWidth = bounds[drag.conversationId]?.width ?: return 0f
    val step = draggedWidth + spacingPx
    return when {
        targetIndex < drag.startIndex && index in targetIndex until drag.startIndex -> step
        targetIndex > drag.startIndex && index in (drag.startIndex + 1)..targetIndex -> -step
        else -> 0f
    }
}

/**
 * The horizontal pixel delta — relative to the dragged tab's own pre-drag
 * position, i.e. directly comparable to [TabDragState.deltaPx] — it will
 * end up at once [targetIndex] is committed: simulates removing the dragged
 * tab from [tabs] and reinserting it at [targetIndex], then sums neighbor
 * widths from [bounds] up to that slot. Computed analytically from the same
 * [bounds] the drag itself already tracked, rather than by waiting for the
 * reordered list to come back down from the app's state, so the dropped tab
 * always has a real place to animate to instead of snapping to zero. Pure
 * and unit-testable; returns null only when a tab's width hasn't been
 * recorded yet (a layout pass hasn't happened), in which case the caller
 * clears the drag immediately rather than settling.
 */
private fun restingDragDeltaPx(
    tabs: List<DesktopConversationTab>,
    bounds: Map<String, TabBoundsPx>,
    drag: TabDragState,
    targetIndex: Int,
    spacingPx: Float,
): Float? {
    val originLeftPx = bounds[drag.conversationId]?.left ?: return null
    val reordered = tabs.map { it.conversationId }.toMutableList()
    val fromIndex = reordered.indexOf(drag.conversationId)
    if (fromIndex < 0) return null
    reordered.removeAt(fromIndex)
    reordered.add(targetIndex.coerceIn(0, reordered.size), drag.conversationId)
    var cursor = 0f
    for (conversationId in reordered) {
        val width = bounds[conversationId]?.width ?: return null
        if (conversationId == drag.conversationId) return cursor - originLeftPx
        cursor += width + spacingPx
    }
    return null
}

@Composable
private fun DesktopConversationTabItem(
    tab: DesktopConversationTab,
    active: Boolean,
    dragging: Boolean,
    visible: Boolean,
    renderOffsetPx: Float,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onBoundsChanged: (left: Float, width: Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(tab.conversationId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // Browser-style tab strip: the active tab is painted in the page background
    // so it reads as the front edge of the content below it, while inactive tabs
    // recede into the title bar (surfaceContainerLow) and only lift on hover.
    Surface(
        onClick = onSelect,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 132.dp, max = 220.dp)
            .padding(top = 5.dp)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.positionInParent().x, coordinates.size.width.toFloat())
            }
            // Draw-only transform: keeps the item's laid-out position (and
            // therefore onGloballyPositioned's report of it) stable while it
            // slides aside for a neighbor. The item currently being dragged
            // is not drawn here at all (see `visible`/DraggedTabOverlay).
            .graphicsLayer { translationX = renderOffsetPx }
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (dragging) 1f else 0f)
            .then(if (dragging) Modifier.shadow(4.dp, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)) else Modifier)
            // This tab strip lives inside Nucleus's custom title bar
            // (DesktopJewelWindow.kt), whose chrome owns a press listener of
            // its own — the native drag-to-move-window gesture — gated on
            // "was this press already consumed by something else." A plain
            // Modifier.draggable defers consuming the initiating press until
            // touch slop is crossed (its down is registered but not claimed),
            // which leaves a window, however small, where a press that starts
            // a drag looks unclaimed to that outer listener. detectTabDragGesture
            // claims the press the instant it lands instead, so no
            // ancestor/sibling chrome can ever treat a tab-drag as "nobody
            // wants this." Foundation's own tap detector inside Surface's
            // onClick doesn't gate on prior consumption of the down (it
            // always claims its own down), so eagerly consuming here doesn't
            // stop a plain click from reaching onSelect; only once real
            // horizontal movement crosses touch slop do we also consume the
            // move, which is what cancels the coexisting tap gesture (same
            // cancellation Foundation's draggable relied on). A drag that
            // starts on the close button never reaches here at all, because
            // the button's own clickable consumes its down first and this
            // handler only tracks the pointer id from its own down.
            .pointerInput(tab.conversationId) {
                detectTabDragGesture(
                    touchSlop = touchSlop,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragStop = onDragStop,
                )
            },
        shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp),
        color = when {
            active -> MaterialTheme.colorScheme.background
            hovered -> MaterialTheme.colorScheme.surfaceContainer
            else -> Color.Transparent
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        interactionSource = interactionSource,
    ) {
        Box {
            DesktopConversationTabLabel(
                tab = tab,
                active = active,
                modifier = Modifier.align(Alignment.Center),
            )
            if (hovered || active) {
                DesktopConversationTabCloseButton(
                    title = tab.title,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * Hand-rolled press+drag+release recognizer for one tab, replacing
 * `Modifier.draggable` — see the comment at this function's call site in
 * [DesktopConversationTabItem] for why. Claims the press immediately, then
 * hands off to [trackTabDrag] to read the rest of the gesture.
 */
private suspend fun PointerInputScope.detectTabDragGesture(
    touchSlop: Float,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStop: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val didDrag = trackTabDrag(down.id, touchSlop, onDragStart, onDrag)
        if (didDrag) onDragStop()
    }
}

/**
 * Reads move events for [pointerId] until it's released, reporting
 * horizontal deltas via [onDrag] once cumulative movement crosses
 * [touchSlop] (calling [onDragStart] the moment it does). Small,
 * sub-threshold moves are read but never consumed, so Surface's own tap
 * detector still sees a clean, unconsumed down-then-up for a plain click.
 * Returns whether a drag was ever started, so the caller knows whether to
 * report [onDragStop] at all.
 */
private suspend fun AwaitPointerEventScope.trackTabDrag(
    pointerId: PointerId,
    touchSlop: Float,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
): Boolean {
    var isDragging = false
    var accumulatedDx = 0f
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        if (!change.pressed) break
        val dx = change.positionChange().x
        if (isDragging) {
            change.consume()
            onDrag(dx)
        } else {
            accumulatedDx += dx
            if (abs(accumulatedDx) > touchSlop) {
                isDragging = true
                change.consume()
                onDragStart()
                onDrag(accumulatedDx)
            }
        }
    }
    return isDragging
}

@Composable
private fun DesktopConversationTabLabel(
    tab: DesktopConversationTab,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = tab.agentName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DesktopConversationTabCloseButton(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClose,
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close $title tab",
            modifier = Modifier.size(14.dp),
        )
    }
}
