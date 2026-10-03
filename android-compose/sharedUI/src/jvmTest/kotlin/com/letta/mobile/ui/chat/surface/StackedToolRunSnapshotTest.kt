@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.surface.ambient.rememberChatAmbient
import com.letta.mobile.ui.theme.LocalReducedMotion
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bglj6.1: a coding agent mid-run, four Bash rounds in, each round under its own App
 * Server run id and the fourth still running. The page draws ONE run: "Working · 4 tools" over
 * one "Running Bash · 4 commands" group, with the page's glow up. Writes PNGs to
 * build/chat-surface-snapshots for review.
 */
class StackedToolRunSnapshotTest {
    private class FixturePort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private fun round(index: Int, running: Boolean = false) = UiMessage(
        id = "call-$index",
        role = "assistant",
        content = "",
        timestamp = secondsAgo(START_SECONDS_AGO - 2 - index * 2),
        runId = "local-run-${99 + index}",
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"./gradlew :sharedLogic:jvmTest --tests round$index"}""",
                result = if (running) null else "BUILD SUCCESSFUL",
                status = if (running) null else "success",
                toolCallId = "tc-$index",
            ),
        ),
    )

    private val midRun = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        messages = (
            listOf(
                UiMessage(id = "u1", role = "user", content = "Run the checks and fix what fails.", timestamp = secondsAgo(START_SECONDS_AGO)),
                UiMessage(
                    id = "a1", role = "assistant", content = "Running the checks.",
                    timestamp = secondsAgo(START_SECONDS_AGO - 2), runId = "local-run-100",
                ),
            ) + (1..3).map { round(it) } + round(4, running = true)
            ).toPersistentList(),
        isLoadingMessages = false,
        isStreaming = true,
        agentName = "Meridian",
        agentId = "agent-1",
    )

    /** The hosts' page background: the shared glow, driven by the page's own state. */
    private val glowingPage = ChatSurfacePlatform(
        pageBackground = { content ->
            Box(Modifier.fillMaxSize()) {
                ChatPanelAmbientGlow(
                    rememberChatAmbient(midRun),
                    AmbientGlowPlacement.AboveComposer { 0.dp },
                    Modifier.fillMaxSize(),
                )
                content()
            }
        },
    )

    private fun render(name: String, presentation: ChatSurfacePresentation, withCanvas: Boolean) = runComposeUiTest {
        // The glow's clock never idles: step frames instead of waiting. Reduced motion holds the
        // glow still (its tint, no breath), so the CPU renderer draws a few shader frames, not one
        // per animation frame.
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ChatSurface(
                            port = FixturePort(midRun),
                            presentation = presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            platform = glowingPage,
                            canvas = if (withCanvas) canvas else null,
                        )
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        // One run, not one per round: one header counting every tool, one group holding them.
        assertEquals(1, onAllNodesWithText("4 tools", substring = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("4 commands", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("1 command", substring = true).fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("Running Bash", substring = true).fetchSemanticsNodes().isNotEmpty())
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    private val canvas: @Composable (ChatCanvasActions) -> Unit = { _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer))
    }

    @Test
    fun fullScreenStacksToolRoundsMidRun() =
        render("full-screen-stacked-tools-dark", ChatSurfacePresentation.ChatFirst, withCanvas = false)

    @Test
    fun dockedStacksToolRoundsMidRun() =
        render("docked-stacked-tools-dark", ChatSurfacePresentation.CanvasFirst, withCanvas = true)

    private companion object {
        /** The turn started this long ago, so the group's clock reads a plausible elapsed time. */
        const val START_SECONDS_AGO = 42L

        fun secondsAgo(seconds: Long): String = java.time.Instant.now().minusSeconds(seconds).toString()

        const val SETTLE_FRAMES = 20
        const val FRAME_MILLIS = 16L
    }
}
