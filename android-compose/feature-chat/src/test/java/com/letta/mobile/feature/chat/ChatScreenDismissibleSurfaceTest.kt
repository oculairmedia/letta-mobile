package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.ui.a2ui.A2uiAction
import kotlinx.collections.immutable.persistentMapOf
import com.letta.mobile.feature.chat.screen.ChatScreen
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.collections.immutable.persistentListOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatScreenDismissibleSurfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `chat screen exposes explicit close affordance for surfaces`() {
        var dismissedSurfaceId: String? = null
        val surfaces = persistentMapOf("test_surface" to A2uiSurfaceState(surfaceId = "test_surface"))

        composeTestRule.setContent {
            // We use ChatScreen or a component that embeds DismissibleA2uiSurface indirectly.
            // But since ChatScreen is the easiest public API we have, we'll provide state that includes our surface.
            ChatScreen(
                uiState = ChatUiState(
                    conversationState = ConversationState.Conversation(
                        conversationId = "conversation-id",
                        title = "Test",
                        updatedAt = "2024-01-01T00:00:00Z"
                    ),
                    messages = persistentListOf(),
                    isStreaming = false,
                    a2uiSurfaces = surfaces
                ),
                onDismissA2uiSurface = { id -> dismissedSurfaceId = id },
                onSendUserMessage = {},
                onSendA2uiAction = { _: A2uiAction -> },
                onLoadMoreChatHistory = {},
                onComposerInputChanged = {},
                onSubagentTodoAction = {_,_,_ -> },
                onRefreshTodos = {},
                onMarkTodoComplete = {},
            )
        }

        // The explicit content description added in ChatScreen.kt
        composeTestRule.onNodeWithContentDescription("Close A2UI surface").performClick()

        assertTrue("Surface should be dismissed via explicit close button", dismissedSurfaceId == "test_surface")
    }
}
