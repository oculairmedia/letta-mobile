package com.letta.mobile.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.pointerInput
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

/** How long the "make room for the dragged tab" shift animation runs for a
 * non-dragged neighbor. The dragged tab itself (and the overlay that draws
 * it) is never animated: it tracks the pointer 1:1 while live, and on drop
 * the state commit is synchronous (verified against the running app — see
 * the "reorder doesn't stick" note on [DraggedTabOverlay]), so clearing the
 * drag state hands back to the row's own already-correct layout with
 * nothing left to animate. */
private const val TabReorderShiftAnimationMillis = 150

/** The row-level tab actions ([DesktopConversationTabRow]'s own
 * `onSelect`/`onClose`), always wired together at each per-tab call site. */
private data class TabRowActions(
    val onSelect: (conversationId: String) -> Unit,
    val onClose: (conversationId: String) -> Unit,
)

/** [TabRowActions] already bound to one tab's id — what
 * [DesktopConversationTabItem] itself actually invokes. */
private data class TabItemActions(
    val onSelect: () -> Unit,
    val onClose: () -> Unit,
)

/** The purely visual state of one rendered tab item: whether it reads as
 * selected, whether it's the one currently floating in
 * [DraggedTabOverlay], whether the row's own copy should even be drawn
 * (see [DesktopConversationTabRowSlot]'s doc), and its current
 * make-room/drag draw offset. */
private data class TabItemDisplay(
    val active: Boolean,
    val dragging: Boolean,
    val visible: Boolean,
    val renderOffsetPx: Float,
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
    // Bounds are keyed by conversationId and refreshed every layout pass, so
    // they stay valid across recompositions without needing to be reset when
    // the tab list itself is reordered. Kept as its own mutable reference
    // (rather than reading it back out of `layout` below) so the
    // onBoundsChanged callback can still assign into it directly —
    // TabLayoutMetrics.bounds is typed as a read-only Map for every other
    // consumer, which is all the drag-preview math ever needs.
    val bounds = remember { mutableStateMapOf<String, TabBoundsPx>() }
    val layout = TabLayoutMetrics(bounds = bounds, spacingPx = with(density) { TabSpacing.toPx() })
    var dragState by remember { mutableStateOf<TabDragState?>(null) }
    val scrollState = rememberScrollState()
    val currentDragState = dragState

    fun beginDrag(tab: DesktopConversationTab, index: Int) {
        dragState = TabDragState(conversationId = tab.conversationId, startIndex = index, deltaPx = 0f)
    }

    fun updateDrag(deltaPx: Float) {
        dragState = dragState?.copy(deltaPx = dragState!!.deltaPx + deltaPx)
    }

    fun endDrag() {
        val drag = dragState ?: return
        val finalTarget = computeDragTargetIndex(tabs, layout.bounds, drag)
        if (finalTarget != drag.startIndex) {
            onReorder(drag.conversationId, finalTarget)
        }
        // The reorder above commits synchronously — LettaDesktopApp.kt's
        // conversationTabsState update and the SideEffect that hands the
        // new tab order back down to this row both land in the very next
        // composition, not some frames later. So there is nowhere useful to
        // animate the overlay to: clearing the drag state right here hands
        // back to the row's own copy of this tab, which recomposes in the
        // same pass already sitting in its correct new slot. Confirmed
        // against the running app rather than assumed — an earlier version
        // of this code animated the overlay toward an analytically-computed
        // resting offset to cover a hand-off delay that, measured, does not
        // exist; that animation was the visible hitch on drop, not a fix
        // for one.
        dragState = null
    }

    val actions = TabRowActions(onSelect = onSelect, onClose = onClose)

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
                    tabs = tabs,
                    dragState = currentDragState,
                    layout = layout,
                    actions = actions,
                    onBoundsChanged = { left, width -> bounds[tab.conversationId] = TabBoundsPx(left, width) },
                    dragCallbacks = TabDragCallbacks(
                        onDragStart = { beginDrag(tab, index) },
                        onDrag = ::updateDrag,
                        onDragStop = ::endDrag,
                    ),
                )
            }
        }

        DraggedTabOverlay(
            tabs = tabs,
            dragState = currentDragState,
            bounds = layout.bounds,
            scrollOffsetPx = scrollState.value.toFloat(),
            activeConversationId = activeConversationId,
            onClose = onClose,
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
    tabs: List<DesktopConversationTab>,
    dragState: TabDragState?,
    layout: TabLayoutMetrics,
    actions: TabRowActions,
    onBoundsChanged: (left: Float, width: Float) -> Unit,
    dragCallbacks: TabDragCallbacks,
) {
    val isDragging = dragState?.conversationId == tab.conversationId
    val targetIndex = dragState?.let { computeDragTargetIndex(tabs, layout.bounds, it) }
    val shiftPx by animateFloatAsState(
        targetValue = if (dragState != null && targetIndex != null && !isDragging) {
            dragShiftPx(index, dragState, targetIndex, layout)
        } else {
            0f
        },
        animationSpec = tween(durationMillis = TabReorderShiftAnimationMillis),
        label = "conversationTabReorderShift",
    )
    DesktopConversationTabItem(
        tab = tab,
        display = TabItemDisplay(
            active = active,
            dragging = false,
            visible = !isDragging,
            renderOffsetPx = if (isDragging) 0f else shiftPx,
        ),
        actions = TabItemActions(
            onSelect = { actions.onSelect(tab.conversationId) },
            onClose = { actions.onClose(tab.conversationId) },
        ),
        onBoundsChanged = onBoundsChanged,
        dragCallbacks = dragCallbacks,
    )
}

/**
 * Renders the actively-dragged tab as a floating overlay above the row,
 * positioned by absolute offset rather than translated in place inside the
 * Row. The Row's own copy of the same tab ([DesktopConversationTabRowSlot]
 * with `visible = false`) stays mounted so it keeps its layout slot, bounds
 * tracking, and pointer input gesture alive; this overlay is purely a
 * visual mirror driven by [dragState] with no gesture handling of its own.
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
 * This overlay simply disappears — [DesktopConversationTabRow.endDrag]
 * clears [dragState] outright, no settle animation — because the reorder
 * it fires on drop commits synchronously (confirmed by instrumenting the
 * running app, not assumed): `LettaDesktopApp.kt`'s `conversationTabsState`
 * update and the `SideEffect` that hands the new tab order back down
 * through `Main.kt`'s `headerChrome` to this row both land in the same
 * composition the drop itself happens in. An earlier version of this code
 * held the overlay for an extra ~150ms, animating it toward an
 * analytically-computed resting offset to cover a hand-off delay that,
 * measured, does not exist; the row underneath had already relaid-out into
 * the new (correct) order for that entire window, so the overlay and the
 * row disagreed on this tab's position for the animation's whole duration
 * — that mismatch, snapped away when the overlay finally cleared, was
 * exactly the visible hitch on release. Clearing immediately means the
 * hand-off is just "this frame draws the overlay one last time at its live
 * position, the very next frame the row's own already-correct copy draws
 * in its place" — no gap for the two to disagree in.
 */
@Composable
private fun DraggedTabOverlay(
    tabs: List<DesktopConversationTab>,
    dragState: TabDragState?,
    bounds: Map<String, TabBoundsPx>,
    scrollOffsetPx: Float,
    activeConversationId: String?,
    onClose: (String) -> Unit,
) {
    if (dragState == null) return
    val draggedTab = tabs.firstOrNull { it.conversationId == dragState.conversationId } ?: return
    val originLeftPx = bounds[dragState.conversationId]?.left ?: return
    val overlayXPx = originLeftPx - scrollOffsetPx + dragState.deltaPx
    DesktopConversationTabItem(
        tab = draggedTab,
        display = TabItemDisplay(
            active = draggedTab.conversationId == activeConversationId,
            dragging = true,
            visible = true,
            renderOffsetPx = 0f,
        ),
        actions = TabItemActions(
            onSelect = {},
            onClose = { onClose(draggedTab.conversationId) },
        ),
        onBoundsChanged = { _, _ -> },
        dragCallbacks = TabDragCallbacks(onDragStart = {}, onDrag = {}, onDragStop = {}),
        // No explicit zIndex needed: this overlay is the Box's second child
        // (after the Row), so it already draws on top by declaration order.
        modifier = Modifier.offset { IntOffset(x = overlayXPx.roundToInt(), y = 0) },
    )
}

@Composable
private fun DesktopConversationTabItem(
    tab: DesktopConversationTab,
    display: TabItemDisplay,
    actions: TabItemActions,
    onBoundsChanged: (left: Float, width: Float) -> Unit,
    dragCallbacks: TabDragCallbacks,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(tab.conversationId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // Browser-style tab strip: the active tab is painted in the page background
    // so it reads as the front edge of the content below it, while inactive tabs
    // recede into the title bar (surfaceContainerLow) and only lift on hover.
    Surface(
        onClick = actions.onSelect,
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
            .graphicsLayer { translationX = display.renderOffsetPx }
            .alpha(if (display.visible) 1f else 0f)
            .zIndex(if (display.dragging) 1f else 0f)
            .then(
                if (display.dragging) {
                    Modifier.shadow(4.dp, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                } else {
                    Modifier
                },
            )
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
                detectTabDragGesture(touchSlop = touchSlop, callbacks = dragCallbacks)
            },
        shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp),
        color = when {
            display.active -> MaterialTheme.colorScheme.background
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
                active = display.active,
                modifier = Modifier.align(Alignment.Center),
            )
            if (hovered || display.active) {
                DesktopConversationTabCloseButton(
                    title = tab.title,
                    onClose = actions.onClose,
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
