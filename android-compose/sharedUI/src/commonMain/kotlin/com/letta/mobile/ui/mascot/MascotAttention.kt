package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import com.letta.mobile.avatar.core.AttentionInterest
import com.letta.mobile.avatar.core.AttentionKey
import com.letta.mobile.avatar.core.AttentionKind
import com.letta.mobile.avatar.core.AttentionRect
import com.letta.mobile.avatar.core.GazeRect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * letta-mobile-te3n1.2 (absorbs letta-mobile-wbin4.9): what the mascot finds worth a look. Any
 * surface marks itself with [mascotAttention]; the rect lands in [MascotIdentityRegistry.attention]
 * and every live mascot's gaze world picks it up (read in a snapshot, never in composition, so a
 * moving card does not recompose the tile). The registry write is the only platform-neutral seam:
 * Android, desktop and web all publish the same way.
 */

/**
 * Publishes this node's window bounds as an attention point of [kind] while [enabled], under [key]
 * (unique per source: a card's request id, a row's key). Retracted when disabled or disposed, so a
 * card that folds away stops drawing the eyes at once. [interest] shapes how the pull fades.
 */
@Composable
fun Modifier.mascotAttention(
    key: AttentionKey,
    kind: AttentionKind,
    enabled: Boolean = true,
    interest: AttentionInterest = kind.interest,
): Modifier {
    val registry = LocalMascotRegistry.current
    val publisher = remember(registry, key) { AttentionPublisher(registry.attention, key) }
    DisposableEffect(publisher, enabled) { onDispose { publisher.retract() } }
    if (!enabled) return this
    return onGloballyPositioned { publisher.publish(kind, interest, it.boundsInWindow().toGazeRect()) }
}

/** One source's slot in the registry: writes only on change, and removes itself. */
internal class AttentionPublisher(
    private val points: MutableMap<AttentionKey, AttentionRect>,
    private val key: AttentionKey,
) {
    fun publish(kind: AttentionKind, interest: AttentionInterest, bounds: GazeRect) {
        val next = AttentionRect(key, kind, bounds, interest)
        if (points[key] != next) points[key] = next
    }

    fun retract() {
        points.remove(key)
    }
}

internal fun Rect.toGazeRect(): GazeRect = GazeRect(left, top, right, bottom)

/**
 * The composer's caret as an attention point (te3n1.2): the text layout says where the caret is,
 * the inner text's coordinates put it in the window. Published while the field has focus; a square
 * the caret's height centred on it, so the point is the caret itself rather than the whole field.
 */
internal class MascotCaret(private val publisher: AttentionPublisher) {
    private var layout: TextLayoutResult? = null
    private var coordinates: LayoutCoordinates? = null
    private var offset = 0
    private var focused = false

    /** The text was laid out again (typed, wrapped). */
    fun onLayout(result: TextLayoutResult) {
        layout = result
        publish()
    }

    /** The inner text moved in the window. */
    fun onPlaced(coords: LayoutCoordinates) {
        coordinates = coords
        publish()
    }

    /** The caret moved to [selectionEnd]. */
    fun onSelection(selectionEnd: Int) {
        if (selectionEnd == offset) return
        offset = selectionEnd
        publish()
    }

    fun onFocus(isFocused: Boolean) {
        if (isFocused == focused) return
        focused = isFocused
        publish()
    }

    private fun publish() {
        val text = layout
        val coords = coordinates
        if (!focused || text == null || coords == null || !coords.isAttached) {
            publisher.retract()
            return
        }
        val cursor = text.getCursorRect(offset.coerceIn(0, text.layoutInput.text.length))
        val center = coords.localToWindow(cursor.center)
        val half = cursor.height / 2f
        val square = GazeRect(center.x - half, center.y - half, center.x + half, center.y + half)
        publisher.publish(AttentionKind.CARET, AttentionKind.CARET.interest, square)
    }
}

/**
 * The primary composer field's caret publisher, told where the caret is ([selectionEnd]) on every
 * composition; null for a field that does not speak for the page.
 */
@Composable
internal fun rememberMascotCaret(primary: Boolean, selectionEnd: Int): MascotCaret? {
    val registry = LocalMascotRegistry.current
    val caret = remember(registry, primary) {
        if (primary) MascotCaret(AttentionPublisher(registry.attention, CARET_KEY)) else null
    }
    SideEffect { caret?.onSelection(selectionEnd) }
    DisposableEffect(caret) { onDispose { caret?.onFocus(false) } }
    return caret
}

private val CARET_KEY = AttentionKey("composer-caret")

/** How long the last touch stays where the mascot looks (te3n1.2): a hand that lifted is soon gone. */
internal val MASCOT_TOUCH_PRESENCE: Duration = 1_500.milliseconds

/**
 * Android's stand-in for a cursor (te3n1.2): the last touch-down or move writes
 * [MascotIdentityRegistry.cursor], so the eyes follow the finger as a desktop mascot follows the
 * pointer, and [expire] clears it again. Desktop's mouse capture is left alone: a cursor written by
 * anything else since the touch is never cleared here.
 */
internal class MascotTouchPresence(private val cursor: MutableState<Offset?>) {
    private var touch: Offset? = null

    fun touched(at: Offset) {
        touch = at
        cursor.value = at
    }

    fun expire() {
        val last = touch ?: return
        touch = null
        if (cursor.value == last) cursor.value = null
    }
}

/** Where a pointer-input surface sits, so its local touch positions can be put in window space. */
private class PlacedSurface {
    var coordinates: LayoutCoordinates? = null

    fun toWindow(local: Offset): Offset {
        val coords = coordinates?.takeIf { it.isAttached } ?: return local
        return coords.localToWindow(local)
    }
}

/**
 * Watches touches on this surface (never consuming them) and publishes the last one as the
 * mascot's cursor for [MASCOT_TOUCH_PRESENCE]. Mouse and stylus hover are the desktop capture's.
 */
@Composable
fun Modifier.mascotTouchPresence(): Modifier {
    val registry = LocalMascotRegistry.current
    val presence = remember(registry) { MascotTouchPresence(registry.cursor) }
    val placed = remember { PlacedSurface() }
    val scope = rememberCoroutineScope()
    return onGloballyPositioned { placed.coordinates = it }.pointerInput(presence) {
        var expiry: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val touch = event.changes.firstOrNull { it.type == PointerType.Touch && it.pressed } ?: continue
                presence.touched(placed.toWindow(touch.position))
                expiry?.cancel()
                expiry = scope.launch {
                    delay(MASCOT_TOUCH_PRESENCE)
                    presence.expire()
                }
            }
        }
    }
}
