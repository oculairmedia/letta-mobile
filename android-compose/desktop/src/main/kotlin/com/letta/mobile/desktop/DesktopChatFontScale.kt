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
import com.letta.mobile.data.chat.display.ChatFontScale
import com.letta.mobile.data.chat.display.ChatFontScaleStore
import com.letta.mobile.desktop.data.DesktopPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val FLOAT_EPSILON = 0.0001f

/**
 * The active chat font scale, mirroring Android's `LocalChatFontScale` from
 * `designsystem`. Desktop cannot consume that one — `designsystem` is an
 * Android module and not a desktop dependency — but the semantics are
 * deliberately identical (1.0 == unmodified) so composables moving into
 * `sharedUI` can read either without behaving differently.
 */
val LocalDesktopChatFontScale = compositionLocalOf { ChatFontScale.DEFAULT }

/**
 * Sets the chat font scale from inside the host (the shared chat page's pinch/zoom reports through
 * `ChatActions.setFontScale`). The value is clamped and snapped, then persisted like a Ctrl+scroll.
 * Outside a [DesktopChatFontScaleHost] it does nothing.
 */
val LocalDesktopChatFontScaleSetter = compositionLocalOf<(Float) -> Unit> { {} }

/**
 * Applies a Ctrl+scroll chat font scale over [content], persisted across
 * launches by the shared [ChatFontScaleStore].
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
    store: ChatFontScaleStore = remember { ChatFontScaleStore(DesktopPreferences.store) },
    content: @Composable () -> Unit,
) {
    var scale by remember {
        mutableFloatStateOf(store.load())
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
    // Surfaces that zoom themselves on Ctrl + wheel (the canvas board) register here and are
    // left alone; the event reaches them in their own pass.
    val wheelZoomRegions = remember { com.letta.mobile.ui.canvas.WheelZoomRegions() }
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
                    val position = event.changes.firstOrNull()?.position
                    if (position != null && wheelZoomRegions.contains(position)) continue
                    val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                    if (abs(delta) < FLOAT_EPSILON) continue
                    scale = ChatFontScale.next(scale, delta)
                    // Consume so the list underneath does not also scroll.
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalDesktopChatFontScale provides scale,
            LocalDesktopChatFontScaleSetter provides { requested -> scale = ChatFontScale.snap(requested) },
            com.letta.mobile.ui.canvas.LocalWheelZoomRegions provides wheelZoomRegions,
        ) {
            content()
        }
    }
}
