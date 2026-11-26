package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Installs [binding] on the window's [ShortcutHub] and matches Ctrl/Cmd chords at the shared shell
 * root. Composer-scoped chords (Shift+Tab) are left for
 * [com.letta.mobile.ui.chat.surface.composer.ComposerPolicy].
 *
 * The system Back is never spent on focus: Compose reads Back as "move focus out", and below this
 * focusable root each press would walk focus one level up (out of a drawer that has closed, out of
 * the prompt, off the canvas board) and never reach the host's Back handlers. While Back is down
 * the root stops being a place focus can move out to, so the walk finds nowhere to go: the press
 * goes on to the host and focus stays where it was (the board keeps its keys).
 */
@Composable
fun rememberShellShortcuts(binding: ShellShortcutBinding?): Modifier {
    val hub = LocalShortcutHub.current
    DisposableEffect(hub, binding) {
        hub.binding = binding
        onDispose { if (hub.binding === binding) hub.binding = null }
    }
    if (binding == null) return Modifier
    val requester = remember { FocusRequester() }
    // Remembered whole: a fresh focusProperties lambda on a recomposition mid-press would make Compose
    // re-check the root's focusability and drop its focus.
    val root = remember(hub, requester) {
        val back = SystemBackPress()
        Modifier
            .onPreviewKeyEvent { event ->
                back.track(event)
                hub.dispatch(event.asStroke())
            }
            .focusRequester(requester)
            // Read when Compose looks for where to move focus, so the change needs no recomposition.
            .focusProperties { canFocus = !back.down }
    }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return root.focusable()
}

/**
 * Whether the system Back is held. A plain field, not snapshot state: it is read while the same key
 * event is dispatched, and must not invalidate the focused root (which would clear its focus).
 */
private class SystemBackPress {
    var down = false
        private set

    fun track(event: KeyEvent) {
        if (event.key == Key.Back) down = event.type == KeyEventType.KeyDown
    }
}

internal fun KeyEvent.asStroke(): ShortcutKeystroke = ShortcutKeystroke(
    key = key,
    down = type == KeyEventType.KeyDown,
    modifiers = ShortcutModifiers(
        ctrlOrMeta = isCtrlPressed || isMetaPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
    ),
)
