package com.letta.mobile.ui.shell.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The conversation board the shared page mounts registers in the host's [CanvasSessionRegistry]
 * (provided as [LocalCanvasSessionRegistry]), so the host's canvas_* tools edit the board on screen
 * (letta-mobile-n2stx.2.1, B0), and leaves it when the page goes.
 */
@OptIn(ExperimentalTestApi::class)
class ShellChatBoardRegistryUiTest {
    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready(CONVERSATION),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    @Test
    fun theMountedBoardIsTheSessionTheRegistryHandsOutUntilThePageLeaves() = runComposeUiTest {
        val registry = CanvasSessionRegistry()
        val shown = mutableStateOf(true)
        val canvasId = CanvasId.forConversation(ConversationId(CONVERSATION))
        val page = ShellChatPage(
            port = Port(),
            host = ChatSurfaceHost(openCanvas = {}),
            board = ShellChatBoard(store = InMemoryCanvasDocumentStore()),
            owner = ShellCanvasOwner(conversationId = CONVERSATION, agentId = AGENT, agentName = "Meridian"),
            dockGeometry = mutableStateOf<ChatDockGeometry?>(null),
        )
        var shownBoard: CanvasId? = null
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCanvasSessionRegistry provides registry) {
                    if (shown.value) {
                        ShellChatDestination(
                            page = page,
                            presentation = ShellChatPresentation(ChatSurfacePresentation.initial(openOnCanvas = true, hasCanvas = true)),
                            look = ShellChatLook(onBoardShown = { shownBoard = it }),
                        )
                    }
                }
            }
        }

        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { registry.get(canvasId) != null && shownBoard == canvasId }
        assertEquals(canvasId, registry.get(canvasId)?.canvasId)

        runOnIdle { shown.value = false }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { registry.get(canvasId) == null }
        assertNull(registry.get(canvasId))
    }

    private companion object {
        const val CONVERSATION = "conv-1"
        const val AGENT = "agent-1"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
