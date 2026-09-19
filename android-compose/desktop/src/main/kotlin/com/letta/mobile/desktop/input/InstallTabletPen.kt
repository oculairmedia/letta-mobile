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
 */
@Composable
internal fun InstallTabletPen(window: Window) {
    val scope = rememberCoroutineScope()
    DisposableEffect(window) {
        val pen = TabletPen(window)
        val started = pen.start(scope)
        onDispose { if (started) pen.stop() }
    }
}
