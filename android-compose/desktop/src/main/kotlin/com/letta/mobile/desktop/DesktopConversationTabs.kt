package com.letta.mobile.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Immutable
internal data class DesktopConversationTab(
    val conversationId: String,
    val title: String,
    val agentName: String,
)

/** Horizontal gap between tabs. */
private val TabSpacing = 4.dp

/**
 * A [DragGestureDetector] that claims the initiating press the instant it
 * lands, instead of Foundation's stock `detectDragGestures` (behind the
 * library's own default, [DragGestureDetector.Press]), which defers
 * consuming it until touch slop is crossed.
 *
 * This tab strip lives inside Nucleus's custom title bar
 * (DesktopJewelWindow.kt), whose chrome owns a press listener of its own —
 * the native drag-to-move-window gesture — gated on "was this press
 * already consumed by something else." A deferred-consumption detector
 * leaves a window, however small, where a press that's about to become a
 * drag looks unclaimed to that outer listener. This closes it: the same
 * fix already proven against real mouse hardware, now adapted to the
 * [DragGestureDetector] shape so it can plug into
 * [sh.calvin.reorderable.ReorderableListItemScope.draggableHandle] instead
 * of a hand-rolled `pointerInput`.
 *
 * Foundation's own tap detector inside `Surface`'s `onClick` doesn't gate
 * on prior consumption of the down (it always claims its own down), so
 * eagerly consuming here doesn't stop a plain click from reaching
 * `onSelect`; only once real movement crosses touch slop do we also
 * consume the move, which is what cancels the coexisting tap gesture.
 */
internal val EagerPressDragGestureDetector = DragGestureDetector {
        onDragStart,
        onDragEnd,
        onDragCancel,
        onDrag,
    ->
    detectEagerDrag(onDragStart, onDragEnd, onDragCancel, onDrag)
}

private suspend fun PointerInputScope.detectEagerDrag(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val callbacks = EagerDragCallbacks(onDragStart, onDragEnd, onDragCancel, onDrag)
        var tracking = EagerDragTrackingState()
        while (true) {
            val change = nextPressedEagerDragChange(down.id) ?: break
            tracking = applyEagerDragDelta(change, tracking, touchSlop, callbacks)
        }
        if (tracking.dragging) callbacks.onDragEnd() else callbacks.onDragCancel()
    }
}

/** The four [DragGestureDetector] callbacks, grouped so [detectEagerDrag]'s
 * loop only has to thread one value instead of four. */
private data class EagerDragCallbacks(
    val onDragStart: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
    val onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
)

/** Cumulative pointer movement tracked while a press hasn't yet crossed
 * touch slop, and whether it has. */
private data class EagerDragTrackingState(
    val dragging: Boolean = false,
    val accumulated: Offset = Offset.Zero,
)

/** Reads the next pointer event and returns [pointerId]'s change from it,
 * or null once that pointer is released (or simply absent) -- the one
 * condition under which [detectEagerDrag]'s loop ends. */
private suspend fun AwaitPointerEventScope.nextPressedEagerDragChange(pointerId: PointerId): PointerInputChange? {
    val event = awaitPointerEvent(PointerEventPass.Main)
    val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
    return change.takeIf { it.pressed }
}

/**
 * Folds one pointer [change] into [state]: while still under touch slop,
 * accumulates its movement without consuming it, leaving a plain click
 * elsewhere in the modifier chain a clean, unconsumed down-then-up to
 * recognize. The instant accumulated movement crosses [touchSlop], or on
 * every change after, it consumes the change and reports through
 * [EagerDragCallbacks.onDragStart]/[EagerDragCallbacks.onDrag] instead.
 */
private fun applyEagerDragDelta(
    change: PointerInputChange,
    state: EagerDragTrackingState,
    touchSlop: Float,
    callbacks: EagerDragCallbacks,
): EagerDragTrackingState {
    val delta = change.positionChange()
    if (state.dragging) {
        change.consume()
        callbacks.onDrag(change, delta)
        return state
    }
    val accumulated = state.accumulated + delta
    if (accumulated.getDistance() <= touchSlop) {
        return state.copy(accumulated = accumulated)
    }
    change.consume()
    callbacks.onDragStart(change.position)
    callbacks.onDrag(change, accumulated)
    return state.copy(dragging = true, accumulated = accumulated)
}

/**
 * Horizontal, drag-to-reorder conversation tab strip, built on
 * `sh.calvin.reorderable`'s lazy-list variant — the same library already
 * used for the mobile dashboard's pinned-items grid
 * (app/.../HomeScreenWidgets.kt's `ReorderablePinnedItemsGrid`). It owns
 * keyed item tracking, neighbor reflow while dragging, drag-from-any-index
 * (including the first), and the drop-settle animation; a hand-rolled
 * version of all four shipped with real bugs (a positional-slot mismatch
 * that corrupted the drop animation, and reflow/first-index bugs in the
 * from-scratch shift math) that this replaces rather than patches around.
 *
 * `LazyRow` specifically, not the library's plain-`Row` variant
 * (`ReorderableRow`) originally used here: `Modifier.horizontalScroll`
 * calls Foundation's `clipScrollableContainer`, an unconditional draw clip
 * to the row's own bounds, which cut off the dragged tab the moment it
 * translated past that boundary — the exact clipping bug this tab strip
 * had before an overlay was built to work around it (since deleted along
 * with the rest of the hand-rolled code). `LazyRow`'s own implementation
 * never applies that clip — it only culls items outside the visible
 * range during layout, not draw-time clipping of what *is* placed — so a
 * dragged item's `graphicsLayer` translation is never cut off, with no
 * portal/overlay needed.
 *
 * The lazy API reports reorders differently than the plain-Row one:
 * `onMove` fires on every neighbor crossed mid-drag (so the local
 * [currentList] mirrors the live preview), not once at drop. The actual
 * [onReorder] callback — (conversationId, targetIndex), matching
 * [com.letta.mobile.data.desktopshell.ConversationTabsReducer.reorder]'s
 * contract — still fires exactly once, when dragging stops, computed from
 * where the dragged tab (tracked by id, not index, since indices shift
 * under it mid-drag) ended up in [currentList].
 *
 * [EagerPressDragGestureDetector] is the one piece kept custom: the
 * library's default gesture detector has the same deferred-press-
 * consumption behavior that originally broke mouse dragging against
 * Nucleus's native title-bar drag-to-move (see that detector's own doc).
 */
@Composable
internal fun DesktopConversationTabRow(
    tabs: List<DesktopConversationTab>,
    activeConversationId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReorder: (conversationId: String, targetIndex: Int) -> Unit = { _, _ -> },
) {
    var currentList by remember(tabs) { mutableStateOf(tabs) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        currentList = currentList.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyRow(
        state = lazyListState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TabSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(currentList, key = { it.conversationId }) { tab ->
            ReorderableItem(reorderableState, key = tab.conversationId) { isDragging ->
                DesktopConversationTabItem(
                    tab = tab,
                    active = tab.conversationId == activeConversationId,
                    dragging = isDragging,
                    onSelect = { onSelect(tab.conversationId) },
                    onClose = { onClose(tab.conversationId) },
                    // onDragStopped, not a DisposableEffect/LaunchedEffect
                    // keyed on `isDragging`: ReorderableCollectionItemScopeImpl's
                    // own draggableHandle sets its *internal* dragging flag
                    // (draggingItemKey, which is what `isDragging` here
                    // reflects) from inside a `coroutineScope.launch { }` --
                    // asynchronous, not guaranteed to have run yet by the time
                    // a fast press-drag-release finishes. A first version of
                    // this used `isDragging`'s own transition to fire
                    // onReorder and it silently never fired in exactly that
                    // scenario (an automated drag, and plausibly any real
                    // drag fast enough to race the launch): onMove still
                    // updated `currentList`'s live preview correctly, but
                    // the commit never happened, invisible unless something
                    // asserts on onReorder itself rather than on the
                    // rendered result. onDragStopped's own plumbing calls
                    // `reorderableLazyCollectionState.onDragStop()`
                    // synchronously, not launched, so it's the reliable
                    // "this item's gesture just ended" signal.
                    modifier = Modifier.draggableHandle(
                        dragGestureDetector = EagerPressDragGestureDetector,
                        onDragStopped = {
                            val targetIndex = currentList.indexOfFirst { it.conversationId == tab.conversationId }
                            if (targetIndex >= 0) onReorder(tab.conversationId, targetIndex)
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun DesktopConversationTabItem(
    tab: DesktopConversationTab,
    active: Boolean,
    dragging: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(tab.conversationId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Browser-style tab strip: the active tab is painted in the page background
    // so it reads as the front edge of the content below it, while inactive tabs
    // recede into the title bar (surfaceContainerLow) and only lift on hover.
    Surface(
        onClick = onSelect,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 132.dp, max = 220.dp)
            .padding(top = 5.dp)
            .then(if (dragging) Modifier.shadow(4.dp, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)) else Modifier),
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
