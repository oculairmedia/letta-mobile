package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The board's context gesture: a long press with a finger, the way Obsidian's canvas opens its
 * menu, or a right click with a mouse. [onContext] gets the position in the board's own space.
 *
 * It only watches, in the Initial pass, until the gesture is recognised - so every ordinary tap
 * and drag still reaches DrawBox untouched. Once it fires it consumes the rest of the gesture:
 * DrawBox's tap detector then sees a consumed release and does nothing, which is what stops a
 * long press in the draw tool from also leaving a dot where the finger was.
 *
 * A stylus is left alone: a pen held still is a pen about to draw.
 */
@Composable
internal fun Modifier.boardContextGesture(onContext: (Offset) -> Unit): Modifier {
    // Read through state so a new lambda each recomposition does not restart a gesture mid-press.
    val latest by rememberUpdatedState(onContext)
    return pointerInput(Unit) { detectBoardContext { latest(it) } }
        .pointerInput(Unit) { detectSecondaryClick { latest(it) } }
}

/**
 * A right click. Watched as raw presses: a secondary-button press is not a "down" to the gesture
 * helpers (they follow the primary button), so awaitFirstDown never reports it.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectSecondaryClick(onContext: (Offset) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val position = event.rightClickPosition() ?: continue
            event.changes.forEach { it.consume() }
            onContext(position)
        }
    }
}

/** Where a right-button mouse press happened, or null for any other event. */
private fun androidx.compose.ui.input.pointer.PointerEvent.rightClickPosition(): Offset? {
    if (type != PointerEventType.Press) return null
    if (!buttons.isSecondaryPressed) return null
    val change = changes.firstOrNull() ?: return null
    return change.position.takeIf { change.type == PointerType.Mouse }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectBoardContext(onContext: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // A stylus held still is a pen about to draw, and a mouse has the right button.
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitHoldEnds(down) }
        if (interrupted == null) {
            onContext(down.position)
            consumeUntilRelease()
        }
    }
}

/**
 * Suspends until the finger that went [down] stops holding still: it lifts, moves past the touch
 * slop, or a second finger joins (a pinch). Always true; a timeout around it is the long press.
 */
private suspend fun AwaitPointerEventScope.awaitHoldEnds(down: PointerInputChange): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        val moved = change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        if (event.changes.size > 1 || change == null || !change.pressed || moved) return true
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilRelease() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/**
 * Moving around the board by touch. Two fingers pinch to zoom and drag to pan, in every tool -
 * DrawBox has no pinch of its own. With [panWithOneFinger] (a phone), one finger dragged from
 * open board ([canPanFrom], in the board's own space) pans as well, which is what a finger on a
 * phone board means; on an element or a selection it is left to DrawBox to move or resize.
 *
 * Nothing is taken until the gesture is recognised: a tap, a long press and a drag that starts on
 * an element all still reach DrawBox. Once it is navigating, it consumes the gesture in the
 * Initial pass, so DrawBox sees a cancelled drag rather than a marquee or a stroke.
 */
@Composable
internal fun Modifier.touchNavigation(
    panWithOneFinger: Boolean,
    canPanFrom: (Offset) -> Boolean,
    onPan: (Offset) -> Unit,
    onZoom: (factor: Float, pivot: Offset) -> Unit,
): Modifier {
    val oneFinger by rememberUpdatedState(panWithOneFinger)
    val canPan by rememberUpdatedState(canPanFrom)
    val pan by rememberUpdatedState(onPan)
    val zoom by rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            val panAllowed = oneFinger && canPan(down.position)
            var navigating = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        navigating = true
                        val factor = event.calculateZoom()
                        if (factor != 1f) zoom(factor, event.calculateCentroid(useCurrent = true))
                        pan(event.calculatePan())
                    }
                    pressed.size == 1 && navigating -> pan(pressed.first().positionChange())
                    pressed.size == 1 && panAllowed -> {
                        val change = pressed.first()
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            navigating = true
                            pan(change.position - down.position)
                        }
                    }
                }
                if (navigating) event.changes.forEach { it.consume() }
            } while (pressed.isNotEmpty())
        }
    }
}

/** What [hollowShapeGrab] needs from the board. Screen positions and deltas, board-space shapes. */
internal class HollowShapeGrab(
    /** The outline-only shape a press at this screen position is inside, or null to leave it alone. */
    val shapeAt: (Offset) -> io.ak1.drawbox.domain.model.Element?,
    val onPick: (io.ak1.drawbox.domain.model.Element) -> Unit,
    val onBegin: () -> Unit,
    val onMoveBy: (screenDelta: Offset) -> Unit,
    val onEnd: () -> Unit,
    val onDoubleTap: (io.ak1.drawbox.domain.model.Element) -> Unit,
)

/**
 * Picking up an outline-only shape by its inside. DrawBox hit-tests such a shape on its stroke
 * alone, so a press in the middle of a rectangle fell through to the board: it could not be
 * dragged from there, and a tap selected nothing. A press inside one is taken here instead - it
 * picks the shape, a drag moves it (one undo step, through DrawBox's own transform intents), and a
 * second tap on it opens its text.
 *
 * Only such presses are taken: anything else, including the shape's stroke and its handles, goes
 * on to DrawBox untouched.
 */
@Composable
internal fun Modifier.hollowShapeGrab(grab: HollowShapeGrab): Modifier {
    val latest by rememberUpdatedState(grab)
    return pointerInput(Unit) {
        var lastTap: Pair<String, Long>? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val shape = latest.shapeAt(down.position) ?: return@awaitEachGesture
            down.consume()
            latest.onPick(shape)
            val dragged = followDrag(down, latest)
            if (dragged) {
                latest.onEnd()
                lastTap = null
                return@awaitEachGesture
            }
            val previous = lastTap
            lastTap = if (previous != null && previous.first == shape.id &&
                down.uptimeMillis - previous.second < viewConfiguration.doubleTapTimeoutMillis
            ) {
                latest.onDoubleTap(shape)
                null
            } else {
                shape.id to down.uptimeMillis
            }
        }
    }
}

/** Follows the pointer that went [down] until it lifts, moving once past the slop; true if it moved. */
private suspend fun AwaitPointerEventScope.followDrag(down: PointerInputChange, grab: HollowShapeGrab): Boolean {
    var dragging = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return dragging
        // Read before consuming: a consumed change reports no movement.
        val delta = change.positionChangeIgnoreConsumed()
        change.consume()
        if (!change.pressed) return dragging
        if (dragging) {
            grab.onMoveBy(delta)
        } else if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            dragging = true
            grab.onBegin()
            grab.onMoveBy(change.position - down.position)
        }
    }
}
