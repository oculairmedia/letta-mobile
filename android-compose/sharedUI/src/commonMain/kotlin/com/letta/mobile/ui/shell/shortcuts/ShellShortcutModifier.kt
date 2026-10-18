package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
        .onPreviewKeyEvent { event -> hub.dispatch(event.asStroke()) }
        .focusRequester(requester)
        .focusable()
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
