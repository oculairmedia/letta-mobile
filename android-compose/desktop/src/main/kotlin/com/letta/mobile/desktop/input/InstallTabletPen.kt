package com.letta.mobile.desktop.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import java.awt.Window

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
 * The scope belongs to the composition; the JOB belongs to this window. That distinction is the
 * whole point: a remembered scope outlives a window swap, so a loop left running on it keeps
 * posting events into the window it was opened against - a window whose Compose scene has been
 * disposed. Cancelling this window's own job on disposal ends that, without an ad-hoc scope of
 * its own to leak.
 */
@Composable
internal fun InstallTabletPen(window: Window) {
    val scope = rememberCoroutineScope()
    DisposableEffect(window) {
        val pen = TabletPen(window)
        val polling = pen.start(scope)
        onDispose {
            // Cancelled BEFORE the handles close: it stops the poll loop and any open that has
            // not finished, so no handle can be registered after this point and outlive the
            // window it belongs to.
            polling?.cancel()
            pen.stop()
        }
    }
}
