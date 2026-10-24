package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.events.Event

private const val COARSE_POINTER_QUERY = "(pointer: coarse)"

/** The browser's `(pointer: coarse)` media query, followed through its `change` events. */
@Composable
actual fun rememberPrimaryPointer(): LettaInput {
    val query = remember { window.matchMedia(COARSE_POINTER_QUERY) }
    var coarse by remember { mutableStateOf(query.matches) }
    DisposableEffect(query) {
        val onChange: (Event) -> Unit = { coarse = query.matches }
        query.addEventListener("change", onChange)
        onDispose { query.removeEventListener("change", onChange) }
    }
    return if (coarse) LettaInput.Touch else LettaInput.Pointer
}
