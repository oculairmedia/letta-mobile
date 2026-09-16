package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import com.letta.mobile.desktop.data.DesktopChatFontScaleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Smallest chat font scale Ctrl+scroll can reach. */
internal const val MIN_CHAT_FONT_SCALE = 0.8f

/** Largest chat font scale Ctrl+scroll can reach. */
internal const val MAX_CHAT_FONT_SCALE = 2.0f

/** Neutral scale — the app's own type sizes, unmodified. */
internal const val DEFAULT_CHAT_FONT_SCALE = 1.0f

/**
 * Multiplicative step per wheel notch, so a notch feels like the same size
 * change at 0.8x as it does at 2.0x.
 */
private const val CHAT_FONT_SCALE_STEP = 1.1f

private const val FLOAT_EPSILON = 0.0001f

/**
 * The active chat font scale, mirroring Android's `LocalChatFontScale` from
 * `designsystem`. Desktop cannot consume that one — `designsystem` is an
 * Android module and not a desktop dependency — but the semantics are
 * deliberately identical (1.0 == unmodified) so composables moving into
 * `sharedUI` can read either without behaving differently.
 */
val LocalDesktopChatFontScale = compositionLocalOf { DEFAULT_CHAT_FONT_SCALE }

/**
 * Next chat font scale for one Ctrl+scroll gesture.
 *
 * [scrollDeltaY] follows Compose's sign convention: positive is a scroll
 * *down*, which scales down. Results are clamped to
 * [MIN_CHAT_FONT_SCALE]..[MAX_CHAT_FONT_SCALE] and snapped to two decimals so
 * repeated multiply/divide round trips cannot drift neutral 1.0 into 0.99999.
 */
internal fun nextChatFontScale(current: Float, scrollDeltaY: Float): Float {
    if (scrollDeltaY == 0f) return current
    val scaled = if (scrollDeltaY > 0f) current / CHAT_FONT_SCALE_STEP else current * CHAT_FONT_SCALE_STEP
    return snapChatFontScale(scaled)
}

/** Clamps and snaps any candidate scale, including one restored from disk. */
internal fun snapChatFontScale(value: Float): Float {
    if (value.isNaN() || value.isInfinite()) return DEFAULT_CHAT_FONT_SCALE
    val clamped = value.coerceIn(MIN_CHAT_FONT_SCALE, MAX_CHAT_FONT_SCALE)
    return (clamped * 100f).roundToInt() / 100f
}

/**
 * Applies a Ctrl+scroll chat font scale over [content], persisted across
 * launches by [DesktopChatFontScaleStore].
 *
 * Scales `fontScale` only, leaving `density` alone: sp-based type grows while
 * dp-based layout (rail widths, icon boxes, the title bar) stays put.
 *
 * The wheel is intercepted on [PointerEventPass.Initial] and consumed, so a
 * Ctrl+scroll over the message list scales instead of scrolling it. Events
 * without Ctrl (or Meta) held are untouched and reach the scrollable beneath
 * as normal.
 */
@Composable
internal fun DesktopChatFontScaleHost(
    store: DesktopChatFontScaleStore = remember { DesktopChatFontScaleStore() },
    content: @Composable () -> Unit,
) {
    var scale by remember {
        mutableFloatStateOf(store.load()?.let(::snapChatFontScale) ?: DEFAULT_CHAT_FONT_SCALE)
    }
    // Persist off the UI thread; a dropped write costs one session's
    // preference, never a frame.
    LaunchedEffect(scale) {
        withContext(Dispatchers.IO) { runCatching { store.save(scale) } }
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, scale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * scale)
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
                    scale = nextChatFontScale(scale, delta)
                    // Consume so the list underneath does not also scroll.
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalDesktopChatFontScale provides scale,
        ) {
            content()
        }
    }
}
