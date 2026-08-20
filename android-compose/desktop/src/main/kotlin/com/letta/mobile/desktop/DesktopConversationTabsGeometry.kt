package com.letta.mobile.desktop

/**
 * Pure drag-position math for the conversation tab strip
 * ([DesktopConversationTabs.kt]): bounds tracking and the two functions
 * that decide where a drag currently belongs and how a neighbor should
 * shift to make room. Kept in its own file, deliberately free of any
 * Compose or gesture imports, so it stays trivially unit-testable and so
 * this specific concern does not add to the density of primitives and
 * parameters in the composables that use it.
 */

/** Left edge and width of a laid-out tab, in px, relative to the tab row own
 * (unscrolled) content coordinates. Captured from onGloballyPositioned so
 * the drag math always reflects the order actually on screen rather than
 * an assumed uniform tab width. */
internal data class TabBoundsPx(val left: Float, val width: Float)

/**
 * In-progress reorder gesture: which tab, the index it started at, and the
 * cumulative horizontal pointer movement since the drag began, in px
 * relative to its pre-drag position. Exists only while a drag is live; see
 * DraggedTabOverlay (in DesktopConversationTabs.kt) for why dropping simply
 * clears it rather than animating anywhere.
 */
internal data class TabDragState(
    val conversationId: String,
    val startIndex: Int,
    val deltaPx: Float,
)

/** [TabBoundsPx] for every laid-out tab plus the spacing constant used to
 * convert between them and px -- the two values the drag-preview math below
 * always needs together. */
internal data class TabLayoutMetrics(
    val bounds: Map<String, TabBoundsPx>,
    val spacingPx: Float,
)

/**
 * Where a tab dragged by [drag] currently belongs among [tabs], expressed as
 * an index into the *pre-drag* order. Compares the dragged tab live center
 * (its last laid-out center plus the accumulated pointer delta) against the
 * still-tabs laid-out centers: it has crossed a neighbor once its center
 * passes that neighbor center. Pure and side-effect free so it can be unit
 * tested without a Compose UI test harness.
 */
internal fun computeDragTargetIndex(
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
 * The px offset a non-dragged tab at [index] should animate to so it
 * visibly makes room for the dragged tab landing at [targetIndex] -- the
 * "other tabs shift to show where it will land" behavior. Tabs strictly
 * between the drag start and target position shift by one
 * dragged-tab-width (plus spacing) toward the vacated slot; everything
 * else stays put.
 */
internal fun dragShiftPx(
    index: Int,
    drag: TabDragState,
    targetIndex: Int,
    layout: TabLayoutMetrics,
): Float {
    if (index == drag.startIndex || targetIndex == drag.startIndex) return 0f
    val draggedWidth = layout.bounds[drag.conversationId]?.width ?: return 0f
    val step = draggedWidth + layout.spacingPx
    return when {
        targetIndex < drag.startIndex && index in targetIndex until drag.startIndex -> step
        targetIndex > drag.startIndex && index in (drag.startIndex + 1)..targetIndex -> -step
        else -> 0f
    }
}
