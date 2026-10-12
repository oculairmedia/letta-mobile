package com.letta.mobile.ui.chat.branch

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * letta-mobile-7mys7: a right-click (the secondary mouse button) runs [onClick], so a pointer user
 * opens a message's actions the way a desktop app does; touch keeps its long-press. The press is
 * consumed so it does not also reach the text selection underneath. Null [onClick] adds nothing.
 */
fun Modifier.onSecondaryClick(onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    return pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onClick()
                }
            }
        }
    }
}
