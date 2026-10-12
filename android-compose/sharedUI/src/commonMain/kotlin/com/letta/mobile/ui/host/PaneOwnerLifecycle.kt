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

/**
 * Whether the page that composes this is started (on screen). A stopped Android page's frame clock
 * is paused, so a menu that closes then never finishes its exit animation and its window stays
 * attached until the page returns: a stopped page's menu leaves at once instead. Always true on
 * desktop and web.
 */
@Composable
internal expect fun ownerIsStarted(): Boolean
