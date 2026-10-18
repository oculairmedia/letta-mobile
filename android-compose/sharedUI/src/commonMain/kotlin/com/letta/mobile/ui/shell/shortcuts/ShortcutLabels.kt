package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key

/** Human-readable chord for the help overlay (Ctrl stands in for Cmd on macOS). */
fun ShortcutChord.label(): String {
    val parts = buildList {
        if (modifiers.ctrlOrMeta) add("Ctrl")
        if (modifiers.alt) add("Alt")
        if (modifiers.shift) add("Shift")
        add(keyName(key))
    }
    return parts.joinToString("+")
}

/** The chord shown for this entry on [platform], with a web alternate noted when it differs. */
fun ShellShortcut.helpLabel(platform: ShortcutPlatform): String {
    val active = chordOn(platform).label()
    val alternate = webAlternate
    if (alternate == null || platform == ShortcutPlatform.Web) return active
    return "$active (web: ${alternate.label()})"
}

internal fun keyName(key: Key): String =
    KEY_NAMES[key] ?: key.toString().substringAfterLast(':').trim().uppercase()

private val KEY_NAMES: Map<Key, String> = mapOf(
    Key.Comma to ",",
    Key.Slash to "/",
    Key.LeftBracket to "[",
    Key.RightBracket to "]",
    Key.DirectionUp to "Up",
    Key.DirectionDown to "Down",
    Key.Tab to "Tab",
    Key.K to "K",
    Key.N to "N",
    Key.L to "L",
    Key.P to "P",
    Key.M to "M",
    Key.F to "F",
    Key.B to "B",
    Key.Y to "Y",
    Key.O to "O",
    Key.R to "R",
)
