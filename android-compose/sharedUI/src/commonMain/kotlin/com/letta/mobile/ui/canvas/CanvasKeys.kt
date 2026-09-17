package com.letta.mobile.ui.canvas

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** What a key press on the board asks for, the way every whiteboard maps its keys. */
internal enum class CanvasKeyAction { DELETE, ESCAPE, UNDO, REDO, DUPLICATE }

/**
 * Maps a key event to a board action, or null. Ctrl on Windows and Linux, Cmd on macOS, both
 * accepted everywhere. Only key-down events act; the event that reached here bubbled up from
 * whatever had focus, so a key a note editor consumed (typing, Backspace in text, its own undo)
 * never arrives.
 */
internal fun canvasKeyAction(event: KeyEvent): CanvasKeyAction? {
    if (event.type != KeyEventType.KeyDown) return null
    val command = event.isCtrlPressed || event.isMetaPressed
    return when {
        command && event.key == Key.Z && event.isShiftPressed -> CanvasKeyAction.REDO
        command && event.key == Key.Z -> CanvasKeyAction.UNDO
        command && event.key == Key.Y -> CanvasKeyAction.REDO
        command && event.key == Key.D -> CanvasKeyAction.DUPLICATE
        !command && (event.key == Key.Delete || event.key == Key.Backspace) -> CanvasKeyAction.DELETE
        !command && event.key == Key.Escape -> CanvasKeyAction.ESCAPE
        else -> null
    }
}
