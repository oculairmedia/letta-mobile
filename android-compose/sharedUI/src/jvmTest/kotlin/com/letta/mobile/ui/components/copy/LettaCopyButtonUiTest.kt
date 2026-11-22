@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.copy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.text.TextClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared Copy button copies exactly its text, then says "Copied"; the keyboard can use it too. */
class LettaCopyButtonUiTest {
    private val text = "line one\n  line two\t"

    private fun ComposeUiTest.show(clipboard: TextClipboard) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                LettaCopyButton(text, "Copy code", Modifier.testTag(TAG))
            }
        }
    }

    @Test
    fun aClickCopiesTheExactTextThenSaysCopied() = runComposeUiTest {
        val clipboard = TextClipboard()
        show(clipboard)

        onNodeWithTag(TAG).assertContentDescriptionEquals("Copy code")
        onNodeWithTag(TAG).performClick()
        waitUntil(timeoutMillis = WAIT_MILLIS) { clipboard.text != null }
        assertEquals(text, clipboard.text)
        onNodeWithTag(TAG).assertContentDescriptionEquals("Copied")
    }

    @Test
    fun enterCopiesFromTheKeyboard() = runComposeUiTest {
        val clipboard = TextClipboard()
        show(clipboard)

        onNodeWithTag(TAG).requestFocus()
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = WAIT_MILLIS) { clipboard.text != null }
        assertEquals(text, clipboard.text)
    }

    private companion object {
        const val TAG = "copy"
        const val WAIT_MILLIS = 5_000L
    }
}
