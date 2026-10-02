package com.letta.mobile.web

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebConnectionState
import kotlin.test.Test

/**
 * letta-mobile-o4ygk.4.5: a browser smoke test of the chat destination. Until a conversation
 * opens it shows the shell's connect state under the shared theme.
 */
@OptIn(ExperimentalTestApi::class)
class WebChatDestinationUiTest {
    private val agent = AgentItemState(id = "agent-1", name = "Nora", model = "anthropic/claude-sonnet")

    @Test
    fun anUnconfiguredBackendShowsTheConnectState() = runComposeUiTest {
        setContent {
            SharedMaterialTheme {
                WebChatDestination(
                    compact = false,
                    roster = WebChatRoster(emptyList(), null, WebConnectionState.Unconfigured, error = null),
                    chat = WebChatLoad.Idle,
                    canvasStore = InMemoryCanvasDocumentStore(),
                    openOnCanvas = true,
                    actions = WebChatShellActions(onAgentSelected = {}, onSettings = {}, onShowAgents = {}),
                )
            }
        }

        onNodeWithTag(WebChatTags.CONNECT_STATE).assertExists()
        onNodeWithText("Configure a backend before starting a conversation.").assertExists()
    }

    @Test
    fun aConversationThatFailedToOpenSaysWhy() = runComposeUiTest {
        setContent {
            SharedMaterialTheme {
                WebChatDestination(
                    compact = true,
                    roster = WebChatRoster(listOf(agent), agent, WebConnectionState.Connected("WebSocket"), error = null),
                    chat = WebChatLoad.Failed("conversation.create timed out"),
                    canvasStore = InMemoryCanvasDocumentStore(),
                    openOnCanvas = true,
                    actions = WebChatShellActions(onAgentSelected = {}, onSettings = {}, onShowAgents = {}),
                )
            }
        }

        onNodeWithText("conversation.create timed out").assertExists()
        onNodeWithText("Nora").assertExists()
    }
}
