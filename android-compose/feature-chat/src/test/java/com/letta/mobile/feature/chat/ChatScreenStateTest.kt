package com.letta.mobile.feature.chat

import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ChatScreenStateTest {
    @Test
    fun `no conversation shows starters only while truly empty and idle`() {
        assertTrue(
            shouldShowStarterPromptsForNoConversation(
                ChatUiState(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(),
                    isStreaming = false,
                ),
            ),
        )
    }

    @Test
    fun `fresh client mode pending optimistic message renders chat content`() {
        assertFalse(
            shouldShowStarterPromptsForNoConversation(
                ChatUiState(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(
                        UiMessage(
                            id = "client-user-1",
                            role = "user",
                            content = "hello fresh",
                            timestamp = "2026-05-03T00:00:00Z",
                        ),
                    ),
                    isStreaming = true,
                ),
            ),
        )
    }

    @Test
    fun `fresh client mode pending typing without message renders chat content`() {
        assertFalse(
            shouldShowStarterPromptsForNoConversation(
                ChatUiState(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(),
                    isStreaming = true,
                ),
            ),
        )
    }

    @Test
    fun `active a2ui surface renders chat content without starter prompts`() {
        assertFalse(
            shouldShowStarterPromptsForNoConversation(
                ChatUiState(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(),
                    isStreaming = false,
                    a2uiSurfaces = persistentMapOf("surface" to A2uiSurfaceState(surfaceId = "surface")),
                ),
            ),
        )
    }
}
