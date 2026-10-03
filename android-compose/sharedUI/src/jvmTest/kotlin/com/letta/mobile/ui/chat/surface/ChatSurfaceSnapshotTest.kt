@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
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

    /** One page to draw and save: its name, presentation, theme, canvas, dock, state and mascot. */
    private data class Scene(
        val name: String,
        val presentation: ChatSurfacePresentation,
        val dark: Boolean,
        val withCanvas: Boolean = false,
        val dock: ChatDockGeometry = ChatDockGeometry.Default,
        /** Null draws the fixture's [state]. */
        val uiState: ChatUiState? = null,
        val withMascot: Boolean = false,
    )

    private fun Scene.snapshot() = runComposeUiTest {
        val uiState = this@snapshot.uiState ?: state
        // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
        if (withMascot) mainClock.autoAdvance = false
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                WithMascot(withMascot) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ChatSurface(
                            port = FixturePort(uiState, composer),
                            presentation = presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            canvas = if (withCanvas) {
                                { _ -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer)) }
                            } else {
                                null
                            },
                            dockGeometry = dock,
                        )
                    }
                }
            }
        }
        if (withMascot) {
            repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        } else {
            waitForIdle()
        }
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    /** The agent's mascot under a transport layer, drawn as a simple stand-in figure. */
    @Composable
    private fun WithMascot(enabled: Boolean, content: @Composable () -> Unit) {
        if (!enabled) return content()
        val shell = FakeMascotShell("agent-1")
        shell.Provide {
            CompositionLocalProvider(LocalMascotHost provides StandInMascotHost) {
                MascotTransportLayer { content() }
            }
        }
    }

    /** Paints a body and a face filling ~60 % of the seat, like the real characters. */
    private object StandInMascotHost : MascotHost {
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = FakeMascotHost.entry(agentId, identity)

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(BODY).background(BODY_COLOR, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(FACE_WIDTH).fillMaxHeight(FACE_HEIGHT).background(Color.White, CircleShape))
                }
            }
        }

        private const val BODY = 0.6f
        private const val FACE_WIDTH = 0.55f
        private const val FACE_HEIGHT = 0.3f
        private val BODY_COLOR = Color(0xFF00AA88)
    }

    private companion object {
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
    }

    @Test
    fun fullScreenLight() = Scene("full-screen-light", ChatSurfacePresentation.ChatFirst, dark = false).snapshot()

    @Test
    fun fullScreenDark() = Scene("full-screen-dark", ChatSurfacePresentation.ChatFirst, dark = true).snapshot()

    @Test
    fun dockedLight() = Scene("docked-light", ChatSurfacePresentation.CanvasFirst, dark = false).snapshot()

    @Test
    fun dockedUnderCanvas() =
        Scene("docked-canvas", ChatSurfacePresentation.CanvasFirst, dark = false, withCanvas = true, withMascot = true).snapshot()

    @Test
    fun dockedPanelMovedAndResized() = Scene(
        "docked-panel-moved",
        ChatSurfacePresentation.CanvasFirst,
        dark = true,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 1f, anchorY = 0.3f, widthDp = 460f, heightDp = 520f),
        withMascot = true,
    ).snapshot()

    /** Dragged against the canvas's top edge: the badge above the panel stays on the canvas. */
    @Test
    fun dockedPanelAtTheTopKeepsItsBadge() = Scene(
        "docked-panel-top",
        ChatSurfacePresentation.CanvasFirst,
        dark = false,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 0f, anchorY = 0f, widthDp = 420f, heightDp = 360f),
        withMascot = true,
    ).snapshot()

    /** Minimised just after a send: the agent thinking over its bar, no panel. */
    @Test
    fun dockedPanelCollapsed() = Scene(
        "docked-panel-collapsed",
        ChatSurfacePresentation.CanvasFirst,
        dark = false,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 0.1f, anchorY = 1f, widthDp = 520f, collapsed = true),
        uiState = state.copy(isAgentTyping = true),
        withMascot = true,
    ).snapshot()

    /** Minimised with the reply in: the bubble beside the agent, tail towards it. */
    @Test
    fun dockedCollapsedBubble() = Scene(
        "docked-collapsed-bubble",
        ChatSurfacePresentation.CanvasFirst,
        dark = false,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, widthDp = 640f, collapsed = true),
        uiState = state.copy(messages = persistentListOf(messages[0], messages[1])),
    ).snapshot()

    @Test
    fun dockedCollapsedBubbleDark() = Scene(
        "docked-collapsed-bubble-dark",
        ChatSurfacePresentation.CanvasFirst,
        dark = true,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, widthDp = 640f, collapsed = true),
        uiState = state.copy(messages = persistentListOf(messages[0], messages[1])),
    ).snapshot()

    /** Docked while the agent works: the panel's ambient glow is the thinking cue. */
    @Test
    fun dockedThinkingDark() = Scene(
        "docked-thinking-dark",
        ChatSurfacePresentation.CanvasFirst,
        dark = true,
        withCanvas = true,
        uiState = state.copy(isAgentTyping = true),
    ).snapshot()

    @Test
    fun dockedThinkingLight() = Scene(
        "docked-thinking-light",
        ChatSurfacePresentation.CanvasFirst,
        dark = false,
        withCanvas = true,
        uiState = state.copy(isAgentTyping = true),
    ).snapshot()

    /** A failed run: the glow takes the error tint. */
    @Test
    fun dockedFailedDark() = Scene(
        "docked-failed-dark",
        ChatSurfacePresentation.CanvasFirst,
        dark = true,
        withCanvas = true,
        uiState = state.copy(error = "The run failed"),
    ).snapshot()

    /** Minimised while the agent thinks: the halo around the mascot, no bubble. */
    @Test
    fun dockedCollapsedThinkingDark() = Scene(
        "docked-collapsed-thinking-dark",
        ChatSurfacePresentation.CanvasFirst,
        dark = true,
        withCanvas = true,
        dock = ChatDockGeometry(anchorX = 0.1f, anchorY = 1f, widthDp = 520f, collapsed = true),
        uiState = state.copy(isAgentTyping = true),
    ).snapshot()

    @Test
    fun fullScreenOverCanvas() =
        Scene("full-screen-canvas", ChatSurfacePresentation.ChatFirst, dark = true, withCanvas = true, withMascot = true).snapshot()
}
