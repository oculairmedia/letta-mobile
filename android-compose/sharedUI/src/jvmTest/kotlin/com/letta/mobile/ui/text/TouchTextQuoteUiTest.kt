@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Quote on the touch selection bar copies through the window's clipboard and leaves it as it was. */
class TouchTextQuoteUiTest {
    @Test
    fun aQuoteSendsTheSelectionAndPutsBackWhatTheClipboardHeld() = runComposeUiTest {
        val clipboard = TextClipboard("held before")
        val toolbar = TouchTextToolbar()
        val sink = QuoteSink()
        var quoted: String? = null
        sink.target = { quoted = it }
        // The selection's Copy, as Compose's: it writes the selected text straight to the clipboard.
        toolbar.showMenu(Rect(0f, 0f, 10f, 10f), { clipboard.text = "the selection" }, null, null, null)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    Box(Modifier.size(400.dp)) { TouchTextToolbarHost(toolbar, sink) }
                }
            }
        }
        onNodeWithText("Quote").performClick()
        waitUntil(timeoutMillis = WAIT_MILLIS) { quoted != null }
        assertEquals("the selection", quoted)
        assertEquals("held before", clipboard.text)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
