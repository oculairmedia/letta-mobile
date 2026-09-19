package com.letta.mobile.desktop.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * The polling coroutine belongs to this effect, not to the composition around it. A scope from
 * `rememberCoroutineScope` outlives a window swap, and the loop it carries keeps posting events
 * into the window it was opened against - which is how the pen came to drive a layout pass on a
 * Compose scene that had already been disposed, and take the app down with it.
 */
@Composable
internal fun InstallTabletPen(window: Window) {
    DisposableEffect(window) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val pen = TabletPen(window)
        pen.start(scope)
        onDispose {
            // Cancel first: it stops the poll loop AND any open that has not finished, so no
            // handle can be registered after this point and then outlive the window.
            scope.cancel()
            pen.stop()
        }
    }
}
