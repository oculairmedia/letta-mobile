package com.letta.mobile.ui.components.tabs

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** One tab: its stable [key], its [title] and the quieter [subtitle] beside it (a conversation's agent). */
@Immutable
data class LettaTab(val key: String, val title: String, val subtitle: String? = null)

/**
 * What the strip can do to a tab, grouped: they always travel together. [onReorder] hears a dragged
 * tab's key and new index once, when the drag ends. [onNew] null hides the trailing "+";
 * [newLabel] is its screen-reader label.
 */
@Immutable
data class LettaTabStripActions(
    val onSelect: (key: String) -> Unit = {},
    val onClose: (key: String) -> Unit = {},
    val onReorder: (key: String, targetIndex: Int) -> Unit = { _, _ -> },
    val onNew: (() -> Unit)? = null,
    val newLabel: String = "New tab",
)

/** Test tags of the strip. */
object LettaTabStripTags {
    const val STRIP = "letta-tab-strip"

    fun tab(key: String): String = "letta-tab-$key"
}

/**
 * letta-mobile-ckmub.3.25 (formerly desktop's conversation tab strip): a horizontal, browser-style
 * strip of closable tabs that reorder by dragging, then an optional "+". The active tab is painted
 * in the page background so it reads as the front edge of the content below; inactive tabs recede
 * and lift on hover.
 *
 * Built on `sh.calvin.reorderable`'s lazy-list variant (the same library as Home's pinned grid): it
 * owns keyed item tracking, neighbour reflow while dragging and the drop-settle animation. The local
 * order mirrors the live preview; [LettaTabStripActions.onReorder] fires once, on drag stop, with
 * where the tab (tracked by key) ended up. `LazyRow`, not a scrolling `Row`: a scrolling row clips
 * its content to its own bounds and cut off the dragged tab.
 *
 * [dragLaneWidth] keeps a blank lane after the strip (outside the `LazyRow`, so a tab can never be
 * dragged into it and cut off): a desktop title bar needs one for native window dragging.
 */
@Composable
fun LettaTabStrip(
    tabs: List<LettaTab>,
    activeKey: String?,
    actions: LettaTabStripActions,
    modifier: Modifier = Modifier,
    dragLaneWidth: Dp = 0.dp,
) {
    var currentList by remember(tabs) { mutableStateOf(tabs) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        currentList = currentList.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Row(modifier = modifier.padding(end = dragLaneWidth).testTag(LettaTabStripTags.STRIP), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = lazyListState,
            modifier = Modifier.weight(1f, fill = false).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(currentList, key = { it.key }) { tab ->
                ReorderableItem(reorderableState, key = tab.key) { isDragging ->
                    LettaTabItem(
                        tab = tab,
                        state = LettaTabItemState(active = tab.key == activeKey, dragging = isDragging),
                        actions = actions,
                        // onDragStopped, not an effect keyed on `isDragging`: the library flips its
                        // dragging flag from a launched coroutine, which a fast press-drag-release can
                        // outrun; onDragStopped runs synchronously when this tab's gesture ends.
                        modifier = Modifier.draggableHandle(
                            dragGestureDetector = EagerPressDragGestureDetector,
                            onDragStopped = {
                                val targetIndex = currentList.indexOfFirst { it.key == tab.key }
                                if (targetIndex >= 0) actions.onReorder(tab.key, targetIndex)
                            },
                        ),
                    )
                }
            }
        }
        actions.onNew?.let { onNew -> NewTabButton(actions.newLabel, onNew) }
    }
}

@Composable
private fun NewTabButton(label: String, onNew: () -> Unit) {
    IconButton(
        onClick = onNew,
        modifier = Modifier.size(LettaDimens.Space.xl).semantics { contentDescription = label },
    ) {
        // Explicit tint: a title bar's content colour is a dimmed chrome role, which made the "+" all but invisible.
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Whether a tab is the open one and whether it is being dragged. */
private data class LettaTabItemState(val active: Boolean, val dragging: Boolean)

@Composable
private fun LettaTabItem(tab: LettaTab, state: LettaTabItemState, actions: LettaTabStripActions, modifier: Modifier = Modifier) {
    val interactionSource = remember(tab.key) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        onClick = { actions.onSelect(tab.key) },
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = TabMinWidth, max = TabMaxWidth)
            .padding(top = LettaDimens.Space.xs)
            .then(if (state.dragging) Modifier.shadow(LettaDimens.Space.xs, TabShape) else Modifier)
            .semantics { selected = state.active }
            .testTag(LettaTabStripTags.tab(tab.key)),
        shape = TabShape,
        color = when {
            state.active -> MaterialTheme.colorScheme.background
            hovered -> MaterialTheme.colorScheme.surfaceContainer
            else -> Color.Transparent
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Box {
            LettaTabLabel(tab, state.active, Modifier.align(Alignment.Center))
            if (hovered || state.active) {
                TabCloseButton(tab.title, onClose = { actions.onClose(tab.key) }, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun LettaTabLabel(tab: LettaTab, active: Boolean, modifier: Modifier = Modifier) {
    // One line, browser-thin: the title, then the subtitle in the quieter colour; the end clears the close button.
    Row(
        modifier = modifier.padding(start = LettaDimens.Space.md, end = LettaDimens.Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f, fill = false),
        )
        tab.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabCloseButton(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // The small hit target around a glyph inside another control, not a button in its own right.
    IconButton(onClick = onClose, modifier = modifier.size(LettaDimens.Control.iconButtonSm)) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close $title tab",
            modifier = Modifier.size(LettaDimens.Control.iconSm),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val TabShape = RoundedCornerShape(topStart = LettaDimens.Space.sm, topEnd = LettaDimens.Space.sm)
private val TabMinWidth = LettaDimens.Orb.hero + LettaDimens.Space.sm
private val TabMaxWidth = LettaDimens.Pane.navWidth

/**
 * A [DragGestureDetector] that claims the initiating press the instant it lands, instead of the
 * library's default, which defers consuming it until the touch slop is crossed. A strip inside a
 * desktop title bar shares the press with the window's native drag-to-move, which only stands back
 * for a press something already claimed; a deferred claim left a window where a press that was
 * about to become a tab drag looked unclaimed to it. A plain click still selects (the tab's own
 * click does not gate on the down's consumption); only movement past the slop also consumes the
 * moves, which cancels the click.
 */
private val EagerPressDragGestureDetector = DragGestureDetector { onDragStart, onDragEnd, onDragCancel, onDrag ->
    detectEagerDrag(EagerDragCallbacks(onDragStart, onDragEnd, onDragCancel, onDrag))
}

/** The four [DragGestureDetector] callbacks, grouped so the drag loop threads one value. */
private class EagerDragCallbacks(
    val onDragStart: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
    val onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
)

private suspend fun PointerInputScope.detectEagerDrag(callbacks: EagerDragCallbacks) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var dragging = false
        var accumulated = Offset.Zero
        while (true) {
            val change = nextPressedChange(down.id) ?: break
            val delta = change.positionChange()
            if (dragging) {
                change.consume()
                callbacks.onDrag(change, delta)
                continue
            }
            accumulated += delta
            if (accumulated.getDistance() > touchSlop) {
                dragging = true
                change.consume()
                callbacks.onDragStart(change.position)
                callbacks.onDrag(change, accumulated)
            }
        }
        if (dragging) callbacks.onDragEnd() else callbacks.onDragCancel()
    }
}

/** [pointerId]'s next change while it stays pressed; null once it lifts (or leaves the event). */
private suspend fun AwaitPointerEventScope.nextPressedChange(pointerId: PointerId): PointerInputChange? {
    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == pointerId } ?: return null
    return change.takeIf { it.pressed }
}
