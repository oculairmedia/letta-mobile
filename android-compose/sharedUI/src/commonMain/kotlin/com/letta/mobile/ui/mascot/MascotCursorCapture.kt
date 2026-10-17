package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Every mascot in the app looks toward the cursor: this captures the pointer once, at the window
 * root, into [registry] (null once it leaves the window) so no surface has to track it itself.
 * Pointer hosts (desktop, the web) wrap their root in it.
 */
@Composable
fun MascotCursorCapture(registry: MascotIdentityRegistry, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    when (e.type) {
                        PointerEventType.Move -> registry.cursor.value = e.changes.firstOrNull()?.position
                        PointerEventType.Exit -> registry.cursor.value = null
                        else -> Unit
                    }
                }
            }
        },
    ) {
        content()
    }
}
