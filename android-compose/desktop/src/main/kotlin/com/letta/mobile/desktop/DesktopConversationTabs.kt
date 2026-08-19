package com.letta.mobile.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Immutable
internal data class DesktopConversationTab(
    val conversationId: String,
    val title: String,
    val agentName: String,
)

/** Horizontal gap between tabs, shared by the layout [Arrangement] and the
 * drag-shift math below so the two stay in sync. */
private val TabSpacing = 4.dp

/** Left edge and width of a laid-out tab, in px, relative to the tab row's
 * own (unscrolled) content coordinates. Captured from `onGloballyPositioned`
 * so the drag math always reflects the order actually on screen rather than
 * an assumed uniform tab width. */
private data class TabBoundsPx(val left: Float, val width: Float)

/** In-progress reorder gesture: which tab is being dragged, the index it
 * started at, and the cumulative horizontal pointer movement since the drag
 * began (used to derive a live target index, not to place the tab itself —
 * the dragged tab is rendered with a draw-only [graphicsLayer] translation so
 * this offset never feeds back into the recorded [TabBoundsPx]). */
private data class TabDragState(
    val conversationId: String,
    val startIndex: Int,
    val deltaPx: Float,
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
    val currentDragState = dragState
    val targetIndex = currentDragState?.let { computeDragTargetIndex(tabs, bounds, it) }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TabSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isDragging = currentDragState?.conversationId == tab.conversationId
            val shiftPx by animateFloatAsState(
                targetValue = if (currentDragState != null && targetIndex != null && !isDragging) {
                    dragShiftPx(index, currentDragState, targetIndex, bounds, spacingPx)
                } else {
                    0f
                },
                animationSpec = tween(durationMillis = 150),
                label = "conversationTabReorderShift",
            )
            val renderOffsetPx = if (isDragging) currentDragState.deltaPx else shiftPx
            DesktopConversationTabItem(
                tab = tab,
                active = tab.conversationId == activeConversationId,
                dragging = isDragging,
                renderOffsetPx = renderOffsetPx,
                onSelect = { onSelect(tab.conversationId) },
                onClose = { onClose(tab.conversationId) },
                onBoundsChanged = { left, width ->
                    bounds[tab.conversationId] = TabBoundsPx(left, width)
                },
                onDragStart = {
                    dragState = TabDragState(conversationId = tab.conversationId, startIndex = index, deltaPx = 0f)
                },
                onDrag = { delta ->
                    dragState = dragState?.let { it.copy(deltaPx = it.deltaPx + delta) }
                },
                onDragStop = {
                    val drag = dragState
                    dragState = null
                    if (drag != null) {
                        val finalTarget = computeDragTargetIndex(tabs, bounds, drag)
                        if (finalTarget != drag.startIndex) {
                            onReorder(drag.conversationId, finalTarget)
                        }
                    }
                },
            )
        }
    }
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

@Composable
private fun DesktopConversationTabItem(
    tab: DesktopConversationTab,
    active: Boolean,
    dragging: Boolean,
    renderOffsetPx: Float,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onBoundsChanged: (left: Float, width: Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStop: () -> Unit,
) {
    val interactionSource = remember(tab.conversationId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Browser-style tab strip: the active tab is painted in the page background
    // so it reads as the front edge of the content below it, while inactive tabs
    // recede into the title bar (surfaceContainerLow) and only lift on hover.
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 132.dp, max = 220.dp)
            .padding(top = 5.dp)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.positionInParent().x, coordinates.size.width.toFloat())
            }
            // Draw-only transform: keeps the item's laid-out position (and
            // therefore onGloballyPositioned's report of it) stable while it
            // visually follows the pointer or slides aside for a neighbor.
            .graphicsLayer { translationX = renderOffsetPx }
            .zIndex(if (dragging) 1f else 0f)
            .then(if (dragging) Modifier.shadow(4.dp, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)) else Modifier)
            // A plain horizontal draggable composes cleanly with Surface's own
            // onClick: Foundation's touch-slop detection consumes the pointer
            // move (not the initial down) once a real drag is recognized,
            // which cancels the coexisting tap gesture — so a click that never
            // moves past the slop still reaches onSelect, and a drag that
            // starts on the close button never reaches here at all, because
            // the button's own clickable consumes its down first.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStarted = { onDragStart() },
                onDragStopped = { onDragStop() },
            ),
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
