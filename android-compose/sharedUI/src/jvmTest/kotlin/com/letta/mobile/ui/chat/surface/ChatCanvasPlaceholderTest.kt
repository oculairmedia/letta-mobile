@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** A new chat gets no board until it has a conversation (letta-mobile-bglj6.1, R1). */
class ChatCanvasPlaceholderTest {
    @Test
    fun noCanvasKeyWithoutAConversation() {
        assertNull(chatCanvasKey(null, 0L))
        assertNull(chatCanvasKey("", 0L))
    }

    @Test
    fun eachConversationGetsItsOwnCanvasKey() {
        assertEquals("canvas:0:conv-1", chatCanvasKey("conv-1", 0L))
        assertNotEquals(chatCanvasKey("conv-1", 0L), chatCanvasKey("conv-2", 0L))
    }

    @Test
    fun placeholderExplainsWhenTheCanvasStarts() = runComposeUiTest {
        setContent { MaterialTheme { ChatCanvasPlaceholder() } }
        onNodeWithText("Send a message to start this conversation’s canvas").assertExists()
    }
}
