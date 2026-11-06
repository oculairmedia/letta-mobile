package com.letta.mobile.ui.components.tabs

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import sh.calvin.reorderable.DragGestureDetector

/**
 * A [DragGestureDetector] that claims the initiating press the instant it lands, instead of the
 * library's default, which defers consuming it until the touch slop is crossed. A strip inside a
 * desktop title bar shares the press with the window's native drag-to-move, which only stands back
 * for a press something already claimed; a deferred claim left a window where a press that was
 * about to become a tab drag looked unclaimed to it. A plain click still selects (the tab's own
 * click does not gate on the down's consumption); only movement past the slop also consumes the
 * moves, which cancels the click.
 */
internal val EagerPressDragGestureDetector = DragGestureDetector { onDragStart, onDragEnd, onDragCancel, onDrag ->
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
            } else {
                accumulated += delta
                if (accumulated.getDistance() > touchSlop) {
                    dragging = true
                    change.consume()
                    callbacks.onDragStart(change.position)
                    callbacks.onDrag(change, accumulated)
                }
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
