package com.letta.mobile.desktop.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.letta.mobile.ui.canvas.CanvasPenRegistry
import java.awt.Window
import kotlinx.coroutines.awaitCancellation

/**
 * Installs the pen on [window] for as long as the composition is alive.
 *
 * Every window that can host the canvas needs this, not just the main one. AWT reports no stylus
 * of its own - measured, see letta-mobile-4i2z9.5 - so a window without the bridge sees the pen as
 * an ordinary mouse: strokes still draw, but at a flat pressure of 1.0 and with no eraser end.
 * That failure looks exactly like a regression in the pen stack, which is why this is shared
 * rather than written out at each window: a new window gets the pen by calling one function, and
 * forgetting it is the kind of thing a reviewer can see.
 *
 * The effect's scope belongs to this window: the poll loop runs in it. That distinction is the
 * whole point: a remembered scope outlives a window swap, so a loop left running on it keeps
 * posting events into the window it was opened against - a window whose Compose scene has been
 * disposed. Cancelling this window's own job on disposal ends that, without an ad-hoc scope of
 * its own to leak.
 */
@Composable
internal fun InstallTabletPen(window: Window, penRegistry: CanvasPenRegistry) {
    LaunchedEffect(window, penRegistry) {
        // The Ink bridge costs the EDT ~300 ms: open it after the window's first frame.
        withFrameNanos { }
        val pen = TabletPen(window, penRegistry)
        val polling = pen.start(this)
        try {
            awaitCancellation()
        } finally {
            // Cancelled BEFORE the handles close, so no handle outlives the window it belongs to.
            polling?.cancel()
            pen.stop()
        }
    }
}
