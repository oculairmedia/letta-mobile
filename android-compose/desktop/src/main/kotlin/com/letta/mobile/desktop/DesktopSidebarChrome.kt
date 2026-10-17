package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent

/**
 * Whether the OS has UI animations disabled. Best-effort: AWT's
 * `win.uiEffects.animationsEnabled` desktop property reflects the Windows
 * "Show animations" accessibility setting; other platforms — and any
 * environment where the property is unavailable — fall back to animating
 * normally rather than guessing. `-Dletta.reducedMotion=true` is available
 * as an explicit override for tests/screenshots.
 */
internal fun desktopPrefersReducedMotion(): Boolean {
    if (System.getProperty("letta.reducedMotion") == "true") return true
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        (toolkit.getDesktopProperty("win.uiEffects.animationsEnabled") as? Boolean)?.not()
    }.getOrNull() ?: false
}

/** Global Ctrl/Cmd+B shortcut for the sidebar toggle, documented alongside Ctrl/Cmd+K for the palette. */
@Composable
internal fun SidebarToggleKeyDispatcherEffect(onToggle: () -> Unit) {
    DisposableEffect(onToggle) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (isSidebarToggleKey(event)) {
                onToggle()
                true
            } else {
                false
            }
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

private fun isSidebarToggleKey(event: KeyEvent): Boolean =
    event.id == KeyEvent.KEY_PRESSED &&
        event.keyCode == KeyEvent.VK_B &&
        (event.isControlDown || event.isMetaDown)
