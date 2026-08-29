package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.math.roundToInt

/** Smallest text scale Ctrl+scroll can reach. */
internal const val MIN_TEXT_ZOOM = 0.8f

/** Largest text scale Ctrl+scroll can reach. */
internal const val MAX_TEXT_ZOOM = 2.0f

/** Neutral scale — the app's own type sizes, unmodified. */
internal const val DEFAULT_TEXT_ZOOM = 1.0f

/**
 * Multiplicative step per wheel notch. Multiplicative (not additive) so a
 * notch feels like the same size change at 0.8x as it does at 2.0x.
 */
private const val TEXT_ZOOM_STEP = 1.1f

/**
 * Next text scale for one Ctrl+scroll gesture.
 *
 * [scrollDeltaY] follows Compose's sign convention: positive is a scroll
 * *down*, which zooms out. Results are clamped to
 * [MIN_TEXT_ZOOM]..[MAX_TEXT_ZOOM] and snapped to two decimals so repeated
 * multiply/divide round trips cannot drift the neutral 1.0 into 0.99999.
 *
 * Pure so the step/clamp/snap behaviour is unit-testable without a
 * composition or a real pointer stream.
 */
internal fun nextTextZoom(current: Float, scrollDeltaY: Float): Float {
    if (scrollDeltaY == 0f) return current
    val scaled = if (scrollDeltaY > 0f) current / TEXT_ZOOM_STEP else current * TEXT_ZOOM_STEP
    val clamped = scaled.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
    return (clamped * 100f).roundToInt() / 100f
}

/**
 * Applies a Ctrl+scroll text zoom over [content].
 *
 * Scales `fontScale` only, leaving `density` alone: sp-based type grows while
 * dp-based layout (rail widths, icon boxes, the title bar) stays put. That is
 * the "scale the text" behaviour rather than browser-style whole-UI zoom —
 * see the note in the PR if we want to switch to the latter.
 *
 * The wheel is intercepted on [PointerEventPass.Initial] and consumed, so a
 * Ctrl+scroll over the message list zooms instead of scrolling it. Events
 * without Ctrl held are left completely untouched and reach the scrollable
 * beneath as normal.
 *
 * Scope is in-session: desktop has no surviving process-restart store for a
 * preference this cheap, so zoom resets to [DEFAULT_TEXT_ZOOM] on relaunch.
 */
@Composable
internal fun DesktopTextZoomHost(content: @Composable () -> Unit) {
    var zoom by remember { mutableFloatStateOf(DEFAULT_TEXT_ZOOM) }
    val baseDensity = LocalDensity.current
    val zoomedDensity = remember(baseDensity, zoom) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * zoom)
    }
    Box(
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Scroll) continue
                    // Meta as well as Ctrl: on macOS Ctrl+scroll is the OS
                    // screen zoom, so Cmd is the app-level convention there.
                    val modifiers = event.keyboardModifiers
                    if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) continue
                    val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                    if (abs(delta) < FLOAT_EPSILON) continue
                    zoom = nextTextZoom(zoom, delta)
                    // Consume so the list underneath does not also scroll.
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalDensity provides zoomedDensity) {
            content()
        }
    }
}

private const val FLOAT_EPSILON = 0.0001f
