package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
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
 * Ctrl/Cmd chords at the shared shell root. Composer-scoped chords (Shift+Tab) are left for
 * [com.letta.mobile.ui.chat.surface.composer.ComposerPolicy].
 */
@Composable
fun rememberShellShortcuts(
    platform: ShortcutPlatform,
    onShortcut: ((ShellShortcutId) -> Unit)?,
): Modifier {
    if (onShortcut == null) return Modifier
    val hub = LocalShortcutHub.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return Modifier
        .onPreviewKeyEvent { event -> dispatchShellKey(event, hub.context(platform), hub, onShortcut) }
        .focusRequester(requester)
        .focusable()
}

internal fun dispatchShellKey(
    event: KeyEvent,
    context: ShortcutContext,
    hub: ShortcutHub,
    onShortcut: (ShellShortcutId) -> Unit,
): Boolean {
    val id = ShortcutRegistry.match(event.asStroke(), context) ?: return false
    if (id == ShellShortcutId.CyclePermission) return false
    if (id == ShellShortcutId.FocusComposer) hub.requestComposer()
    onShortcut(id)
    return true
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
