@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.branch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.text.TextClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

/** A message menu's Copy puts the whole message on the window's clipboard, though picking it closes the menu. */
class MessageMenuCopyUiTest {
    @Test
    fun copyPutsTheMessageOnTheClipboardAfterTheMenuCloses() = runComposeUiTest {
        val clipboard = TextClipboard()
        val message = UiMessage(id = "m1", role = "assistant", content = "The whole reply", timestamp = "2026-01-01T00:00:00Z")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    val menu = rememberMessageMenu(message, ChatSurfaceCapabilities(), NoMenuActions, sendAgain = false)
                    Box(Modifier.size(400.dp)) { MessageMenuPopup(menu) }
                    LaunchedEffect(menu) { menu.openMenu?.invoke() }
                }
            }
        }
        onNodeWithText("Copy").performClick()
        waitUntil(timeoutMillis = WAIT_MILLIS) { clipboard.text != null }
        assertEquals("The whole reply", clipboard.text)
    }

    private object NoMenuActions : MessageMenuActions {
        override fun forkFromMessage(message: UiMessage, name: String?) = Unit

        override fun editAndResend(message: UiMessage) = Unit

        override fun rerunInFork(message: UiMessage) = Unit

        override fun sendAgain(message: UiMessage) = Unit
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
