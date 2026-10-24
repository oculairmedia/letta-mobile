package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

// letta-mobile-ckmub.17.36: one rule, so the shell's containers and the chat page never disagree.

/**
 * The one idiom rule every platform follows. A window draws the phone's
 * idiom ([LettaInput.Touch]: the flush composer bar, sheets, long-press menus, the header's search
 * icon) when its primary pointer is coarse or the window is compact; only a wide window driven by
 * a fine pointer draws the pointer idiom (the composer card, popovers, hover actions). A narrow
 * desktop or browser window is therefore drawn exactly as the phone is.
 *
 * [pointer] is the platform's primary pointer, which the window root reports as [LettaHost.input];
 * [compact] is the workspace's own fold decision (below [com.letta.mobile.ui.theme.LettaDimens.Pane.wideBreakpoint]).
 */
fun lettaIdiom(pointer: LettaInput, compact: Boolean): LettaInput = if (compact) LettaInput.Touch else pointer

/**
 * The platform's primary pointer, what a window root reports as [LettaHost.input]: Touch on
 * Android, Pointer on the desktop, and in a browser whatever its `(pointer: coarse)` media query
 * says right now (a phone or tablet browser is coarse; it follows the query's changes).
 */
@Composable
expect fun rememberPrimaryPointer(): LettaInput

/**
 * Re-provides the host in effect with its input resolved by [lettaIdiom] for a window that is
 * [compact] or not. The workspace calls it once around everything it draws; a compact header the
 * workspace hoists out of itself (the desktop title bar) calls it again so it agrees with the page.
 */
@Composable
fun ProvideLettaIdiom(compact: Boolean, content: @Composable () -> Unit) {
    val host = LocalLettaHost.current
    val idiom = lettaIdiom(host.input, compact)
    // Remembered: a new host every frame would recompose everything under the static local.
    val resolved = remember(host, idiom) { if (idiom == host.input) host else host.copy(input = idiom) }
    CompositionLocalProvider(LocalLettaHost provides resolved, content = content)
}
