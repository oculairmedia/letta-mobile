@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test

/** The whole shared page's own layers (letta-mobile-bglj6.1). */
class ChatSurfaceUiTest {
    internal class TestPort(state: ChatUiState, composer: ChatComposerUiState = ChatComposerUiState(canSend = true)) :
        ChatSessionPort {
        val ui = MutableStateFlow(state)
        override val uiState: StateFlow<ChatUiState> = ui
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(composer)
        val recording = RecordingChatActions()
        override val actions: ChatActions = recording
    }

    private val ready = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        messages = persistentListOf(
            UiMessage(id = "u1", role = "user", content = "Hello there", timestamp = "2026-09-30T18:02:00Z"),
        ),
    )

    private fun ComposeUiTest.show(
        port: TestPort,
        presentation: ChatSurfacePresentation,
        platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
        withCanvas: Boolean = true,
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = port,
                        presentation = presentation,
                        onIntent = {},
                        host = ChatSurfaceHost(),
                        platform = platform,
                        canvas = if (withCanvas) ({ _ -> Text("CANVAS") }) else null,
                    )
                }
            }
        }
    }

    @Test
    fun timelineOverlayDrawsOverTheFullScreenTimeline() = runComposeUiTest {
        show(
            TestPort(ready),
            ChatSurfacePresentation.ChatFirst,
            platform = ChatSurfacePlatform(timelineOverlay = { Text("RINGS") }),
        )
        onNodeWithText("RINGS").assertExists()
        onNodeWithText("Hello there").assertExists()
    }

    @Test
    fun timelineOverlayIsNotDrawnWhileDocked() = runComposeUiTest {
        show(
            TestPort(ready),
            ChatSurfacePresentation.CanvasFirst,
            platform = ChatSurfacePlatform(timelineOverlay = { Text("RINGS") }),
        )
        onNodeWithText("RINGS").assertDoesNotExist()
    }
}
