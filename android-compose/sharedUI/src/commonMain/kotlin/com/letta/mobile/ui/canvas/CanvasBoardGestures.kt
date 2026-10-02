package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.ak1.drawbox.presentation.PanFling
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
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
internal fun Modifier.boardContextGesture(
    onContext: (Offset) -> Unit,
    /**
     * A finger's long press, when it means something other than a right click. It answers
     * [LongPressOutcome.BOX] when the drag that follows draws a selection box.
     */
    onLongPress: (Offset) -> LongPressOutcome = { onContext(it); LongPressOutcome.DONE },
    /** The selection box a [LongPressOutcome.BOX] press drags out; see [SelectionBoxDrag]. */
    onBox: (SelectionBoxDrag) -> Unit = {},
): Modifier {
    // Read through state so a new lambda each recomposition does not restart a gesture mid-press.
    val latest by rememberUpdatedState(onContext)
    val latestLong by rememberUpdatedState(onLongPress)
    val latestBox by rememberUpdatedState(onBox)
    return pointerInput(Unit) { detectBoardContext({ latestLong(it) }, { latestBox(it) }) }
        .pointerInput(Unit) { detectSecondaryClick { latest(it) } }
}

/** What a finger's long press went on to do. */
internal enum class LongPressOutcome {
    /** It did its job (a menu, a selection); the rest of the press is swallowed. */
    DONE,

    /** The finger now drags a selection box out from where it was held. */
    BOX,
}

/**
 * A selection box being dragged out from [from] to [to], in the board's own space. [to] is null
 * while the finger has not left the slop; [released] is the finger lifting, when a box selects.
 */
internal data class SelectionBoxDrag(val from: Offset, val to: Offset?, val released: Boolean)

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

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectBoardContext(
    onLongPress: (Offset) -> LongPressOutcome,
    onBox: (SelectionBoxDrag) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // A stylus held still is a pen about to draw, and a mouse has the right button.
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitHoldEnds(down) }
        if (interrupted == null) {
            when (onLongPress(down.position)) {
                LongPressOutcome.DONE -> consumeUntilRelease()
                LongPressOutcome.BOX -> dragSelectionBox(down, onBox)
            }
        }
    }
}

/**
 * Follows the held finger until it lifts, reporting the box from where it was held to where it
 * is. Everything is consumed, so the tool underneath neither draws nor moves anything.
 */
private suspend fun AwaitPointerEventScope.dragSelectionBox(down: PointerInputChange, onBox: (SelectionBoxDrag) -> Unit) {
    var last: Offset? = null
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || !change.pressed) break
        if (last != null || movedPastSlop(change, down)) {
            last = change.position
            onBox(SelectionBoxDrag(down.position, last, released = false))
        }
    }
    onBox(SelectionBoxDrag(down.position, last, released = true))
}

/**
 * Two quick finger taps on open board zoom in there: [canZoomAt] says where that is (in the
 * board's own space), [onZoom] eases the board in. The second tap is consumed so the tool beneath
 * does not also act on it; a double tap on an element is left alone, for DrawBox to open its text.
 *
 * Only fingers: a mouse double click and a pen double tap mean other things on the board.
 */
@Composable
internal fun Modifier.touchDoubleTapZoom(
    canZoomAt: (Offset) -> Boolean,
    onZoom: (Offset) -> Unit,
): Modifier {
    val canZoom by rememberUpdatedState(canZoomAt)
    val zoom by rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        val taps = CanvasDoubleTap(
            tapSlop = viewConfiguration.touchSlop,
            doubleTapSlop = viewConfiguration.touchSlop * DOUBLE_TAP_REACH,
            tapMillis = viewConfiguration.longPressTimeoutMillis,
            doubleTapMillis = viewConfiguration.doubleTapTimeoutMillis,
        )
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch) {
                taps.clear()
                return@awaitEachGesture
            }
            val second = taps.down(down.position.x, down.position.y, down.uptimeMillis) != null &&
                canZoom(down.position)
            if (second) down.consume()
            val up = awaitTapUp(down, consume = second)
            when {
                up == null -> taps.clear()
                second -> {
                    taps.clear()
                    zoom(down.position)
                }
                else -> taps.up(up.position.x, up.position.y, up.uptimeMillis)
            }
        }
    }
}

/**
 * The lift of [down] when it was a tap: the only finger, lifted before it moved past the slop.
 * Null when it moved, a second finger joined, or it was cancelled. [consume] swallows it.
 */
private suspend fun AwaitPointerEventScope.awaitTapUp(down: PointerInputChange, consume: Boolean): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (consume) event.changes.forEach { it.consume() }
        if (event.changes.count { it.pressed } > 1) return null
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (movedPastSlop(change, down)) return null
        if (!change.pressed) return change
    }
}

/** How far apart, in touch slops, the two taps of a double tap may land. */
private const val DOUBLE_TAP_REACH = 3f

/**
 * Suspends until the finger that went [down] stops holding still: it lifts, moves past the touch
 * slop, or a second finger joins (a pinch). Always true; a timeout around it is the long press.
 */
private suspend fun AwaitPointerEventScope.awaitHoldEnds(down: PointerInputChange): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return true
        val change = event.changes.firstOrNull { it.id == down.id } ?: return true
        if (!change.pressed || movedPastSlop(change, down)) return true
    }
}

private fun AwaitPointerEventScope.movedPastSlop(change: PointerInputChange, down: PointerInputChange): Boolean =
    (change.position - down.position).getDistance() > viewConfiguration.touchSlop

private suspend fun AwaitPointerEventScope.consumeUntilRelease() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/**
 * On a phone, one finger dragged from open board ([canPanFrom], in the board's own space) pans:
 * that is what a finger on a phone board means, and it spares the bar a pan tool. On an element
 * or a selection the drag is left to DrawBox, to move or resize. Two-finger pinch and pan are
 * DrawBox's own.
 *
 * Nothing is taken until the finger has moved past the slop: a tap and a long press still reach
 * DrawBox. Once it is panning it consumes the gesture in the Initial pass, so DrawBox sees a
 * cancelled drag rather than a marquee. A second finger ends it, for DrawBox's pinch.
 */
@Composable
internal fun Modifier.touchNavigation(
    enabled: Boolean,
    canPanFrom: (Offset) -> Boolean,
    onPan: (Offset) -> Unit,
): Modifier {
    val on by rememberUpdatedState(enabled)
    val canPan by rememberUpdatedState(canPanFrom)
    val pan by rememberUpdatedState(onPan)
    // A thrown board coasts on after the finger lifts; the next touch anywhere catches it.
    val scope = rememberCoroutineScope()
    val fling = remember(scope) { PanFling(scope) { delta -> pan(delta) } }
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            fling.stop()
            if (down.type == PointerType.Touch && on && canPan(down.position)) panWithOneFinger(down, fling) { pan(it) }
        }
    }
}

/**
 * Pans with the finger that went [down] once it moves past the slop, until it lifts (and throws the
 * board on at its speed) or a second finger joins for DrawBox's pinch (no throw). A finger lifted
 * without panning was a tap: nothing is taken.
 */
private suspend fun AwaitPointerEventScope.panWithOneFinger(down: PointerInputChange, fling: PanFling, pan: (Offset) -> Unit) {
    var panning = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty() && panning) return fling.release(event.changes.first().uptimeMillis)
        if (pressed.size != 1) return
        val change = pressed.first()
        if (panning) {
            pan(change.positionChange())
            fling.track(change.uptimeMillis, change.position)
        } else if (movedPastSlop(change, down)) {
            panning = true
            fling.begin(change.uptimeMillis, change.position)
            pan(change.position - down.position)
        }
        if (panning) change.consume()
    }
}
