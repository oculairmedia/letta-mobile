package com.letta.mobile.desktop.phone

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** A phone preview shortcut: its keys as the overlay spells them, and what it does. */
internal enum class PhoneShortcut(val keys: String, val description: String) {
    Keyboard("Ctrl+K", "Show / hide the on-screen keyboard"),
    AutoKeyboard("Ctrl+Shift+K", "Keyboard follows text focus (on / off)"),
    Rotate("Ctrl+R", "Rotate (portrait / landscape)"),
    FontScale("Ctrl+F", "Font size: 1.0, 1.15, 1.3, 1.5, 2.0, 0.85"),
    DisplaySize("Ctrl+Shift+D", "Display size: default, large, larger"),
    Theme("Ctrl+L", "Light / dark"),
    ReducedMotion("Ctrl+M", "Reduced motion (remove animations)"),
    TouchPointer("Ctrl+T", "Mouse acts as touch (on / off)"),
    Frame("Ctrl+Shift+F", "Device frame (on / off)"),
    Screenshot("Ctrl+Shift+S", "Save a screenshot of the phone"),
    Help("F1", "Show / hide this list"),
    ;

    companion object {
        /** The shortcut [event] presses, or null; only key-down events count. Cmd works for Ctrl on macOS. */
        fun of(event: KeyEvent): PhoneShortcut? {
            if (event.type != KeyEventType.KeyDown) return null
            if (event.key == Key.F1) return Help
            val command = event.isCtrlPressed || event.isMetaPressed
            if (!command) return null
            val shift = event.isShiftPressed
            return when (event.key) {
                Key.K -> if (shift) AutoKeyboard else Keyboard
                Key.R -> Rotate.takeUnless { shift }
                Key.F -> if (shift) Frame else FontScale
                Key.D -> DisplaySize.takeIf { shift }
                Key.L -> Theme.takeUnless { shift }
                Key.M -> ReducedMotion.takeUnless { shift }
                Key.T -> TouchPointer.takeUnless { shift }
                Key.S -> Screenshot.takeIf { shift }
                else -> null
            }
        }
    }
}

/**
 * Applies [shortcut] to this state and announces the result. A [Screenshot][PhoneShortcut.Screenshot]
 * is a request the window takes (see PhoneScreenshots).
 */
internal fun DesktopPhoneState.apply(shortcut: PhoneShortcut) {
    when (shortcut) {
        PhoneShortcut.Keyboard -> {
            keyboardVisible = !keyboardVisible
            announce(if (keyboardVisible) "Keyboard up" else "Keyboard down")
        }
        PhoneShortcut.AutoKeyboard -> {
            autoKeyboard = !autoKeyboard
            announce(if (autoKeyboard) "Keyboard follows text focus" else "Keyboard only on Ctrl+K")
        }
        PhoneShortcut.Rotate -> {
            landscape = !landscape
            announce(if (landscape) "Landscape" else "Portrait")
        }
        PhoneShortcut.FontScale -> {
            fontScale = PHONE_FONT_SCALES.next(fontScale)
            announce("Font size ${formatScale(fontScale)}")
        }
        PhoneShortcut.DisplaySize -> {
            displaySize = PHONE_DISPLAY_SIZES.next(displaySize)
            announce("Display size ${formatScale(displaySize)}")
        }
        PhoneShortcut.Theme -> {
            dark = !dark
            announce(if (dark) "Dark theme" else "Light theme")
        }
        PhoneShortcut.ReducedMotion -> {
            reducedMotion = !reducedMotion
            announce(if (reducedMotion) "Reduced motion on" else "Reduced motion off")
        }
        PhoneShortcut.TouchPointer -> {
            touchPointer = !touchPointer
            announce(if (touchPointer) "Mouse acts as touch" else "Mouse acts as a mouse")
        }
        PhoneShortcut.Frame -> deviceFrame = !deviceFrame
        PhoneShortcut.Help -> showShortcuts = !showShortcuts
        PhoneShortcut.Screenshot -> screenshotRequests += 1
    }
}

private fun List<Float>.next(current: Float): Float {
    val index = indexOfFirst { kotlin.math.abs(it - current) < SCALE_EPSILON }
    return this[(index + 1).mod(size)]
}

private const val SCALE_EPSILON = 0.001f
