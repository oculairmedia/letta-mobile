package com.letta.mobile.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * Hand-rolled press+drag+release recognition for one conversation tab
 * ([DesktopConversationTabs.kt]), replacing Modifier.draggable -- see the
 * comment at this gesture call site in DesktopConversationTabItem for why.
 * Kept in its own file so the low-level pointer-event bookkeeping does not
 * add to the composables own complexity.
 */

/** The three callbacks a tab drag recognizer ([detectTabDragGesture])
 * reports through, always wired together as a unit from
 * DesktopConversationTabRow down to DesktopConversationTabItem. */
internal data class TabDragCallbacks(
    val onDragStart: () -> Unit,
    val onDrag: (deltaPx: Float) -> Unit,
    val onDragStop: () -> Unit,
)

/**
 * Claims the initiating press immediately, then hands off to
 * [trackTabDrag] to read the rest of the gesture.
 */
internal suspend fun PointerInputScope.detectTabDragGesture(touchSlop: Float, callbacks: TabDragCallbacks) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val didDrag = trackTabDrag(down.id, touchSlop, callbacks)
        if (didDrag) callbacks.onDragStop()
    }
}

/**
 * Reads move events for [pointerId] until it is released, folding each one
 * through [applyDragDelta] to report horizontal deltas via
 * [TabDragCallbacks.onDrag] once cumulative movement crosses [touchSlop].
 * Returns whether a drag was ever started, so the caller knows whether to
 * report [TabDragCallbacks.onDragStop] at all.
 */
private suspend fun AwaitPointerEventScope.trackTabDrag(
    pointerId: PointerId,
    touchSlop: Float,
    callbacks: TabDragCallbacks,
): Boolean {
    var tracking = TabDragTrackingState()
    while (true) {
        val change = nextPressedChange(pointerId) ?: break
        tracking = applyDragDelta(change, tracking, touchSlop, callbacks)
    }
    return tracking.isDragging
}

/** Reads the next pointer event and returns [pointerId] change from it, or
 * null once that pointer is released (or simply absent) -- the one
 * condition under which [trackTabDrag] loop ends. */
private suspend fun AwaitPointerEventScope.nextPressedChange(pointerId: PointerId): PointerInputChange? {
    val event = awaitPointerEvent(PointerEventPass.Main)
    val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
    return change.takeIf { it.pressed }
}

/** Cumulative horizontal movement tracked while a tab press has not yet
 * crossed touch slop, and whether it has. */
private data class TabDragTrackingState(
    val isDragging: Boolean = false,
    val accumulatedDx: Float = 0f,
)

/**
 * Folds one pointer [change] into [state]: while still under touch slop,
 * accumulates its horizontal movement without consuming it -- leaving
 * Surface own tap detector a clean, unconsumed down-then-up to recognize a
 * plain click from. The instant accumulated movement crosses [touchSlop],
 * or on every change after, it consumes the change and reports through
 * [TabDragCallbacks.onDragStart]/[TabDragCallbacks.onDrag] instead.
 */
private fun applyDragDelta(
    change: PointerInputChange,
    state: TabDragTrackingState,
    touchSlop: Float,
    callbacks: TabDragCallbacks,
): TabDragTrackingState {
    val dx = change.positionChange().x
    if (state.isDragging) {
        change.consume()
        callbacks.onDrag(dx)
        return state
    }
    val accumulatedDx = state.accumulatedDx + dx
    if (abs(accumulatedDx) <= touchSlop) {
        return state.copy(accumulatedDx = accumulatedDx)
    }
    change.consume()
    callbacks.onDragStart()
    callbacks.onDrag(accumulatedDx)
    return state.copy(isDragging = true, accumulatedDx = accumulatedDx)
}
