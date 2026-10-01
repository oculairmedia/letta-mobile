@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.RecordingChatActions
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Renders the whole shared page from fixtures and writes PNGs to build/chat-surface-snapshots,
 * so a reviewer can look at the page without a backend. It asserts only that it rendered.
 */
class ChatSurfaceSnapshotTest {
    private class FixturePort(state: ChatUiState, composer: ChatComposerUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(composer)
        override val actions: ChatActions = RecordingChatActions()
    }

    private val messages = persistentListOf(
        UiMessage(id = "u1", role = "user", content = "Plan a shopping list for a taco night for six.", timestamp = "2026-09-30T18:02:00Z"),
        UiMessage(
            id = "a1",
            role = "assistant",
            content = "Here's a list for **six people**:\n\n- 2 lb ground beef\n- 18 tortillas\n- Salsa, lime, cilantro",
            timestamp = "2026-09-30T18:02:09Z",
            runId = "run-1",
            toolCalls = listOf(
                UiToolCall(name = "canvas_add_element", arguments = "{\"kind\":\"checklist\"}", result = "ok", status = "success", toolCallId = "t1"),
            ),
        ),
        UiMessage(id = "u2", role = "user", content = "Add guacamole too.", timestamp = "2026-09-30T18:03:00Z"),
    )

    private val state = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        messages = messages,
        isLoadingMessages = false,
        agentName = "Meridian",
        agentId = "agent-1",
    )

    private val composer = ChatComposerUiState(
        text = "Make it vegetarian",
        canSend = true,
        model = ChatModelUiState(currentHandle = "anthropic/claude-sonnet", currentLabel = "Sonnet"),
    )

    private fun snapshot(name: String, presentation: ChatSurfacePresentation, dark: Boolean) = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ChatSurface(
                        port = FixturePort(state, composer),
                        presentation = presentation,
                        onIntent = {},
                        host = ChatSurfaceHost(openCanvas = {}),
                    )
                }
            }
        }
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun fullScreenLight() = snapshot("full-screen-light", ChatSurfacePresentation.ChatFirst, dark = false)

    @Test
    fun fullScreenDark() = snapshot("full-screen-dark", ChatSurfacePresentation.ChatFirst, dark = true)

    @Test
    fun dockedLight() = snapshot("docked-light", ChatSurfacePresentation.CanvasFirst, dark = false)
}
