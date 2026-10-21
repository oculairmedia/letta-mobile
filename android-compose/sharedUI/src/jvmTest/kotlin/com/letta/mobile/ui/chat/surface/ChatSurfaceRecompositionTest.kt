@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
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
 * Typing must not recompose the timeline. Hosts rebuild their host
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

    /**
     * A streamed token changes the chat state many times a second; only what shows the stream may
     * follow it. The page's layers over the canvas (the layer stack, the panel's layers, the
     * companion seat) never read the stream, so they must not run again while a reply streams in,
     * and neither may settled timeline rows. Composable bodies are counted with the runtime's
     * composition tracer, which reports each one as it runs.
     */
    @Test
    fun streamingTokensDoNotRecomposeThePageLayersOrSettledRows() = runComposeUiTest {
        val streaming = UiMessage(id = "reply-1", role = "assistant", content = "", timestamp = "2026-09-30T18:03:00Z")
        val uiState = MutableStateFlow(state.copy(messages = persistentListOf(state.messages.single(), streaming), isStreaming = true))
        val port = object : ChatSessionPort {
            override val uiState: StateFlow<ChatUiState> = uiState
            override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState(canSend = true))
            override val actions: ChatActions = RecordingChatActions()
        }
        val runs = CompositionRunCounter(PAGE_LAYERS)
        runs.install()
        try {
            setContent {
                MaterialTheme {
                    Box(Modifier.size(width = 960.dp, height = 720.dp)) {
                        ChatSurface(
                            port = port,
                            presentation = ChatSurfacePresentation.CanvasFirst,
                            onIntent = freshIntent(),
                            host = freshHost(),
                            canvas = { _ -> Box(Modifier.fillMaxSize()) },
                        )
                    }
                }
            }
            waitForIdle()
            val settled = runs.snapshot()
            val settledResolves = resolves
            assertTrue(settled.values.all { it > 0 }, "every page layer should have composed: $settled")

            var text = ""
            repeat(TOKENS) { token ->
                text += "token$token "
                runOnIdle {
                    uiState.value = uiState.value.copy(
                        messages = persistentListOf(state.messages.single(), streaming.copy(content = text)),
                    )
                }
                waitForIdle()
            }

            assertEquals(settled, runs.snapshot(), "streamed tokens re-ran page layers")
            assertEquals(settledResolves, resolves, "a streamed token recomposed a settled timeline row")
        } finally {
            runs.uninstall()
        }
    }

    /** Counts how often each named composable's body runs, through the runtime's composition tracer. */
    @OptIn(InternalComposeTracingApi::class)
    private class CompositionRunCounter(private val names: List<String>) : CompositionTracer {
        private val counts = names.associateWith { 0 }.toMutableMap()

        fun install() = Composer.setTracer(this)

        fun uninstall() = Composer.setTracer(null)

        fun snapshot(): Map<String, Int> = synchronized(counts) { counts.toMap() }

        override fun isTraceInProgress(): Boolean = true

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(' ').substringAfterLast('.')
            if (name in counts) synchronized(counts) { counts[name] = counts.getValue(name) + 1 }
        }

        override fun traceEventEnd() = Unit
    }
    private companion object {
        const val TOKENS = 8

        /** The page's layers over the canvas: none of them shows the stream. */
        val PAGE_LAYERS = listOf("ChatSurfaceLayers", "CanvasWithChat", "CanvasChatLayers", "CompanionSeat")
    }
}
