package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

/**
 * letta-mobile-ztdpf: asks an anchored pane (a popover, a menu) to close when the screen that
 * opened it stops: its destination is navigated away from, or the app goes to the background.
 *
 * On Android an anchored pane is its own window ("Pop-Up Window", a sub-panel of the activity).
 * A window left open while its page is off screen keeps the page's composition, and everything it
 * captured, reachable from a native GC root (the window's back-callback binder). So the pane closes
 * through its own [onDismiss]: the caller's open state resets too, and the pane does not reappear
 * by itself on return. Desktop and web keep their windows' behaviour: there it does nothing.
 */
@Composable
internal expect fun DismissWhenOwnerStops(onDismiss: () -> Unit)
