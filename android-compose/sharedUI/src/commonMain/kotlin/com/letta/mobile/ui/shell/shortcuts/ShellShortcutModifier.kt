package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
 * focusable root each press would only walk focus one level up (out of a drawer that has closed,
 * out of the prompt) and never reach the host's Back handlers. Back first takes focus to this root,
 * which has nowhere further out to go, so the press goes on to the host, and the root keeps the
 * focus the window's chords need.
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
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return Modifier
        .onPreviewKeyEvent { event ->
            if (isSystemBack(event)) runCatching { requester.requestFocus() }
            hub.dispatch(event.asStroke())
        }
        .focusRequester(requester)
        .focusable()
}

/** The system Back going down: the key Compose would otherwise turn into a focus move. */
internal fun isSystemBack(event: KeyEvent): Boolean = event.key == Key.Back && event.type == KeyEventType.KeyDown

internal fun KeyEvent.asStroke(): ShortcutKeystroke = ShortcutKeystroke(
    key = key,
    down = type == KeyEventType.KeyDown,
    modifiers = ShortcutModifiers(
        ctrlOrMeta = isCtrlPressed || isMetaPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
    ),
)
