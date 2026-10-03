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
        assertNull(chatCanvasKey(null))
        assertNull(chatCanvasKey(""))
    }

    @Test
    fun eachConversationGetsItsOwnCanvasKey() {
        assertEquals("canvas:conv-1", chatCanvasKey("conv-1"))
        assertNotEquals(chatCanvasKey("conv-1"), chatCanvasKey("conv-2"))
    }

    @Test
    fun placeholderExplainsWhenTheCanvasStarts() = runComposeUiTest {
        setContent { MaterialTheme { ChatCanvasPlaceholder() } }
        onNodeWithText("Send a message to start this conversation’s canvas").assertExists()
    }
}
