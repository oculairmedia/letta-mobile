package com.letta.mobile.desktop.avatar.pet

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * Win32 extended-style plumbing for the pet window. The PRD's pet contract
 * (P6): never activates or steals focus, hidden from Alt-Tab, and optionally
 * click-through. macOS (NSPanel non-activating) comes later behind the same
 * calls — the spec is behavior-level, this file is the Windows mechanism.
 */
object PetWindowStyles {
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_NOACTIVATE = 0x08000000

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /**
     * Apply the always-on styles: no-activate (clicks never focus-steal) and
     * tool-window (no Alt-Tab / taskbar entry). AWT's focusableWindowState
     * covers the Java side; this covers the OS side.
     */
    fun applyBaseStyles(window: ComposeWindow) {
        window.focusableWindowState = false
        exStyle(window) { style -> style or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW }
    }

    /**
     * Toggle OS-level click-through: with it on, every mouse event passes to
     * whatever is underneath — including our own hover affordances, so the
     * caller owns the escape hatch (auto-revert / global hotkey later).
     * WS_EX_LAYERED is left untouched: AWT's per-pixel transparency owns it.
     */
    fun setClickThrough(window: ComposeWindow, enabled: Boolean) {
        exStyle(window) { style ->
            if (enabled) style or WS_EX_TRANSPARENT else style and WS_EX_TRANSPARENT.inv()
        }
    }

    private inline fun exStyle(window: ComposeWindow, transform: (Int) -> Int) {
        if (!isWindows) return
        val handle = window.windowHandle
        if (handle == 0L) return
        val hwnd = WinDef.HWND(Pointer(handle))
        val user32 = User32.INSTANCE
        val current = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val next = transform(current)
        if (next == current) return
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, next)
        // SetWindowLong alone leaves Windows' cached non-client/frame, taskbar,
        // and hit-test state stale until a SetWindowPos with SWP_FRAMECHANGED —
        // without it the tool-window/no-activate and click-through flips can be
        // ignored on an already-visible window (the exact behavior this spike
        // validates). No move/resize/z-order/activation change, just a reframe.
        user32.SetWindowPos(
            hwnd,
            null,
            0, 0, 0, 0,
            WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or
                WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or WinUser.SWP_NOOWNERZORDER,
        )
    }
}
