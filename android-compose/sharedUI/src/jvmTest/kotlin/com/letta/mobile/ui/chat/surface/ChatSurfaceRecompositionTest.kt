@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: typing must not recompose the timeline. Hosts rebuild their host
 * callbacks and intent lambda on every recomposition (Android's ChatScreen recomposes per
 * keystroke); the page must still hand rows the same callbacks so settled rows skip.
 *
 * The row under test is an inter-agent message: its provenance label resolves the agent names
 * through [ChatSurfaceHost.resolveAgentName] while it composes, so each call is one composition
 * of that row.
 */
class ChatSurfaceRecompositionTest {
    private class TypingPort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        val draft = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val composer: StateFlow<ChatComposerUiState> = draft
        override val actions: ChatActions = RecordingChatActions()
    }

    private val state = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        messages = persistentListOf(
            UiMessage(
                id = "inbound-1",
                role = "user",
                content = "Deploy finished cleanly.",
                timestamp = "2026-09-30T18:02:00Z",
                agentMessageProvenance = AgentMessageProvenance(
                    direction = AgentMessageDirection.INBOUND,
                    fromAgentId = "agent-meridian",
                    toAgentId = "agent-pm",
                    msgId = "msg-1",
                    deliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
                ),
            ),
        ),
    )

    private var resolves = 0

    /** The host's name roster: remembered by real hosts, so the same resolver every time. */
    private val resolver: (String) -> String? = { _ ->
        resolves++
        null
    }

    /**
     * A brand-new host per call, as a host that does not remember its callbacks builds one: fresh
     * navigation lambdas around the same name resolver.
     */
    private fun freshHost(): ChatSurfaceHost = ChatSurfaceHost(
        openAgent = { _ -> },
        openAgentPane = {},
        resolveAgentName = resolver,
    )

    private fun freshIntent(): (ChatSurfaceIntent) -> Unit = { _ -> }

    @Test
    fun typingInTheComposerDoesNotRecomposeSettledRows() = runComposeUiTest {
        val port = TypingPort(state)
        setContent {
            // Read the draft here, so this host recomposes on every keystroke like ChatScreen.
            val draft by port.composer.collectAsState()
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = port,
                        presentation = ChatSurfacePresentation.ChatFirst,
                        onIntent = freshIntent(),
                        host = freshHost(),
                        platform = ChatSurfacePlatform(showKeyboardHints = draft.text.isEmpty()),
                    )
                }
            }
        }
        waitForIdle()
        val settledResolves = resolves
        assertTrue(settledResolves > 0, "the provenance row should have composed")

        for (text in listOf("h", "he", "hel", "hell", "hello")) {
            runOnIdle { port.draft.value = port.draft.value.copy(text = text) }
            waitForIdle()
        }

        assertEquals(settledResolves, resolves, "a keystroke recomposed the timeline row")
    }
}
