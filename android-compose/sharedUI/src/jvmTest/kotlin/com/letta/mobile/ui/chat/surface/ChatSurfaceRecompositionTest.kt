@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
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
        setPointerContent {
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
        val runs = CompositionRunCounter(PAGE_LAYERS + "DockedReplyCard")
        runs.install()
        try {
            setPointerContent {
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

            val after = runs.snapshot()
            assertEquals(
                settled.filterKeys { it in PAGE_LAYERS },
                after.filterKeys { it in PAGE_LAYERS },
                "streamed tokens re-ran page layers",
            )
            assertTrue(after.getValue("DockedReplyCard") > settled.getValue("DockedReplyCard"), "the transcript saw no stream")
            assertEquals(settledResolves, resolves, "a streamed token recomposed a settled timeline row")
        } finally {
            runs.uninstall()
        }
    }

    @Test
    fun defaultLaneDoesNotRecomposeForKeyboardHeightChanges() = runComposeUiTest {
        var height by mutableStateOf(720.dp)
        val port = TypingPort(state)
        val host = freshHost()
        val intent = freshIntent()
        val runs = CompositionRunCounter(listOf("ChatLane", "ChatLaneContent", "ChatTimeline"))
        runs.install()
        try {
            setPointerContent {
                MaterialTheme {
                    Box(Modifier.size(480.dp, height)) {
                        ChatSurface(port, ChatSurfacePresentation.ChatFirst, intent, host)
                    }
                }
            }
            waitForIdle()
            val before = runs.snapshot()
            assertTrue(before.getValue("ChatTimeline") > 0, "the real transcript must be mounted")
            assertTrue(before.getValue("ChatLaneContent") > 0, "lane content must be counted")
            listOf(400.dp, 720.dp).forEach { next ->
                runOnIdle { height = next }
                waitForIdle()
            }
            val after = runs.snapshot()
            println("KEYBOARD_HEIGHT before=$before after=$after")
            assertEquals(before, after, "keyboard constraints recomposed default lane/transcript content")
        } finally {
            runs.uninstall()
        }
    }

    @Test
    fun equalLayoutOnUnrelatedWorkspaceRecompositionKeepsChatLocalReaders() = runComposeUiTest {
        var title by mutableStateOf("Before")
        var pageRuns = 0
        val config = ShellLayoutConfig.Default
        val port = TypingPort(state)
        val surfaceHost = freshHost()
        val intent = freshIntent()
        val host = LettaShellWorkspaceHost(
            actions = ShellWorkspaceNavigationActions(),
            slots = LettaShellWorkspaceSlots(
                main = { modifier ->
                    val lane = LocalChatLane.current
                    val content = LocalChatModuleContent.current
                    SideEffect { pageRuns++ }
                    Box(modifier) {
                        // Read both locals, as a page that can draw an extra module does.
                        if (lane.isEmpty()) content(0, Modifier)
                        ChatSurface(port, ChatSurfacePresentation.ChatFirst, intent, surfaceHost)
                    }
                },
                agentCard = {},
                sidePane = {},
                overlays = {},
            ),
            chrome = ShellWindowChrome.Hoisted {},
            drawer = ShellWorkspaceDrawer(),
            events = ShellWorkspaceEvents(),
        )
        setPointerContent {
            MaterialTheme {
                LettaShellWorkspace(
                    LettaShellWorkspaceState(
                        header = ShellWorkspaceHeaderState(title = title),
                        navigation = ShellWorkspaceNavigation(
                            ShellLibraryRailState(emptyList(), com.letta.mobile.ui.shell.rail.ShellRailFocus()),
                            ShellAgentPanelState(com.letta.mobile.ui.shell.sidebar.ShellPanelAgent(name = "Ada")),
                        ),
                        layout = ShellWorkspaceLayout(lanes = config.copy(lanes = config.lanes.toMap())),
                    ),
                    host,
                    // Unrelated host chrome consumes space; the stored layout remains equal.
                    Modifier.size(960.dp, if (title == "Before") 720.dp else 700.dp),
                )
            }
        }
        waitForIdle()
        val before = pageRuns
        assertTrue(before > 0)
        runOnIdle { title = "After" }
        waitForIdle()
        println("EQUAL_LAYOUT before=$before after=$pageRuns")
        assertEquals(before, pageRuns, "equal layout invalidated the chat page local readers")
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
            val qualified = info.substringBefore(' ')
            val name = if (qualified.startsWith("com.letta.mobile.ui.chat.surface.ChatLane.<anonymous>")) {
                "ChatLaneContent"
            } else {
                qualified.substringAfterLast('.')
            }
            if (name in counts) synchronized(counts) { counts[name] = counts.getValue(name) + 1 }
        }

        override fun traceEventEnd() = Unit
    }
    private companion object {
        const val TOKENS = 8

        /** The page's layers over the canvas: none of them shows the stream. */
        val PAGE_LAYERS = listOf("ChatSurfaceLayers", "CanvasWithChat", "CanvasChatLayers", "CompanionSeat", "ChatLane")
    }
}
