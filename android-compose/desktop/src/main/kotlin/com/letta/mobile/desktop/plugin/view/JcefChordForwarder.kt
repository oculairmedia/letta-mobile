package com.letta.mobile.desktop.plugin.view

import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * letta-mobile-k5nbq.11: a windowed browser takes its keys in its native window, so they never pass
 * AWT. A Ctrl/Cmd chord the page left unhandled, typed outside the page's own text fields, is posted
 * to the browser's AWT component as a key press; the window's AWT shortcut dispatcher reads it there.
 */
internal object JcefChordForwarder : CefKeyboardHandlerAdapter() {
    override fun onKeyEvent(browser: CefBrowser?, event: CefKeyboardHandler.CefKeyEvent?): Boolean {
        val component = browser?.uiComponent ?: return false
        val key = event?.let(::forwardedChord) ?: return false
        SwingUtilities.invokeLater {
            val press = KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                key.modifiers,
                key.keyCode,
                KeyEvent.CHAR_UNDEFINED,
                KeyEvent.KEY_LOCATION_STANDARD,
            )
            component.dispatchEvent(press)
        }
        return false
    }
}

/** An AWT key code and extended modifiers. */
internal data class AwtChord(val keyCode: Int, val modifiers: Int)

/** The AWT chord of a key-down with Ctrl or Cmd held outside an editable field; null for anything else. */
internal fun forwardedChord(event: CefKeyboardHandler.CefKeyEvent): AwtChord? {
    val down = event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN ||
        event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_KEYDOWN
    if (!down || event.focus_on_editable_field) return null
    return cefChord(event.windows_key_code, event.modifiers)
}

/** [windowsKeyCode] and CEF's event flags as an AWT chord, when Ctrl or Cmd is held. */
internal fun cefChord(windowsKeyCode: Int, cefModifiers: Int): AwtChord? {
    var modifiers = 0
    if (cefModifiers and CEF_CONTROL != 0) modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
    if (cefModifiers and CEF_COMMAND != 0) modifiers = modifiers or InputEvent.META_DOWN_MASK
    if (modifiers == 0) return null
    if (cefModifiers and CEF_SHIFT != 0) modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
    if (cefModifiers and CEF_ALT != 0) modifiers = modifiers or InputEvent.ALT_DOWN_MASK
    return AwtChord(WINDOWS_OEM_KEYS[windowsKeyCode] ?: windowsKeyCode, modifiers)
}

// cef_event_flags_t.
private const val CEF_SHIFT = 1 shl 1
private const val CEF_CONTROL = 1 shl 2
private const val CEF_ALT = 1 shl 3
private const val CEF_COMMAND = 1 shl 7

/**
 * Windows virtual-key codes (which CEF reports on every OS) whose AWT codes differ. Letters,
 * digits, arrows and Tab share their codes.
 */
private val WINDOWS_OEM_KEYS: Map<Int, Int> = mapOf(
    0xBC to KeyEvent.VK_COMMA,
    0xBF to KeyEvent.VK_SLASH,
    0xDB to KeyEvent.VK_OPEN_BRACKET,
    0xDD to KeyEvent.VK_CLOSE_BRACKET,
)
