package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenAutoScrollTest {

    @Test
    fun `streaming tail growth snaps instead of animating`() {
        val previous = chatState(messages = listOf(uiMessage(content = "Hel")), isStreaming = true)
            .toAutoScrollSignal()
        val current = chatState(messages = listOf(uiMessage(content = "Hello")), isStreaming = true)
            .toAutoScrollSignal()

        assertEquals(ChatAutoScrollAction.SNAP, decideChatAutoScrollAction(previous, current))
    }

    @Test
    fun `stream end without content change does not retrigger scroll`() {
        val previous = chatState(messages = listOf(uiMessage(content = "Hello")), isStreaming = true)
            .toAutoScrollSignal()
        val current = chatState(messages = listOf(uiMessage(content = "Hello")), isStreaming = false)
            .toAutoScrollSignal()

        assertEquals(ChatAutoScrollAction.NONE, decideChatAutoScrollAction(previous, current))
    }

    @Test
    fun `non streaming content change animates`() {
        val previous = chatState(messages = listOf(uiMessage(content = "Hello")), isStreaming = false)
            .toAutoScrollSignal()
        val current = chatState(messages = listOf(uiMessage(content = "Hello there")), isStreaming = false)
            .toAutoScrollSignal()

        assertEquals(ChatAutoScrollAction.ANIMATE, decideChatAutoScrollAction(previous, current))
    }

    private fun chatState(messages: List<UiMessage>, isStreaming: Boolean): ChatUiState = ChatUiState(
        messages = messages.toImmutableList(),
        isStreaming = isStreaming,
    )

    private fun uiMessage(
        id: String = "m-1",
        role: String = "assistant",
        content: String,
    ): UiMessage = UiMessage(
        id = id,
        role = role,
        content = content,
        timestamp = "2026-05-01T00:00:00Z",
    )
}
