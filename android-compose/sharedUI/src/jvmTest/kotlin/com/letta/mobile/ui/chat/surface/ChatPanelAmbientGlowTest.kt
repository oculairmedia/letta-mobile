@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowAnimatedKey
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.CHAT_AMBIENT_GLOW_TAG
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.surface.ambient.rememberChatAmbientStatus
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The canvas chat window's thinking cue: the ambient glow, driven by the run's state. */
class ChatPanelAmbientGlowTest {
    private class MutablePort(state: ChatUiState) : ChatSessionPort {
        val state = MutableStateFlow(state)
        override val uiState: StateFlow<ChatUiState> = this.state
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private val idle = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        agentName = "Meridian",
        agentId = "agent-1",
    )

    @Test
    fun docked_panel_glows_while_the_agent_thinks_and_not_at_rest() = runComposeUiTest {
        val port = MutablePort(idle)
        setContent {
            StillTheme {
                ChatSurface(
                    port = port,
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(0)

        port.state.value = idle.copy(isAgentTyping = true)
        waitForIdle()
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(1)

        // The run ends: the Completed afterglow holds for the shared decay, then the glow goes.
        port.state.value = idle
        waitForIdle()
        mainClock.advanceTimeBy(AmbientMotion.holdMillis(AmbientMotionStatus.Completed) + SETTLE_MILLIS)
        waitForIdle()
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_failed_run_keeps_the_glow_on() = runComposeUiTest {
        setContent {
            StillTheme {
                ChatSurface(
                    port = MutablePort(idle.copy(error = "boom")),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                )
            }
        }
        // Failure is not transient: the glow holds its error tint until the error clears.
        waitForIdle()
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun the_minimised_dock_draws_no_glow_while_thinking() = runComposeUiTest {
        setContent {
            StillTheme {
                ChatSurface(
                    port = MutablePort(idle.copy(isAgentTyping = true)),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                    dockGeometry = ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, widthDp = 520f, collapsed = true),
                )
            }
        }
        waitForIdle()
        // No glow at all: neither the folded panel's nor a halo behind the mascot (its motion is the cue).
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(0)
        // Still announced to a screen reader.
        onNodeWithTag(DOCK_COLLAPSED_THINKING_TAG).assertExists()
    }

    @Test
    fun the_glow_moves_unless_motion_is_reduced() = runComposeUiTest {
        var reduced by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ChatPanelAmbientGlow(
                        ambient = ChatAmbient(AmbientMotionStatus.Running),
                        placement = AmbientGlowPlacement.AboveComposer { 0.dp },
                        modifier = Modifier.size(GLOW_SIZE),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(AmbientGlowAnimatedKey, true))
        reduced = true
        waitForIdle()
        onNodeWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(AmbientGlowAnimatedKey, false))
    }

    @Test
    fun reduced_motion_holds_a_still_tint() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.size(GLOW_SIZE).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        ChatPanelAmbientGlow(
                            ambient = ChatAmbient(AmbientMotionStatus.Running),
                            placement = AmbientGlowPlacement.AboveComposer { 0.dp },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val first = onNodeWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).captureToImage().toAwtImage()
        mainClock.advanceTimeBy(STILL_CHECK_MILLIS)
        waitForIdle()
        val later = onNodeWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).captureToImage().toAwtImage()
        assertEquals(first.width, later.width)
        var tinted = false
        for (x in 0 until first.width) {
            for (y in 0 until first.height) {
                // A still frame is drawn once directly and then from a cached image of itself; the
                // two may round the shader's dither differently by a level, which is not motion.
                assertTrue(
                    channelDistance(first.getRGB(x, y), later.getRGB(x, y)) <= DITHER_TOLERANCE,
                    "pixel ($x, $y) moved under reduced motion",
                )
                if (first.getRGB(x, y) != first.getRGB(0, 0)) tinted = true
            }
        }
        assertTrue(tinted, "the still glow still shows its tint")
    }

    @Test
    fun the_status_follows_the_run_like_the_hosts() = runComposeUiTest {
        var thinking by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var status = AmbientMotionStatus.Idle
        setContent { status = rememberChatAmbientStatus(thinking, error) }
        waitForIdle()
        assertEquals(AmbientMotionStatus.Idle, status)
        thinking = true
        waitForIdle()
        assertEquals(AmbientMotionStatus.Running, status)
        thinking = false
        waitForIdle()
        assertEquals(AmbientMotionStatus.Completed, status)
        mainClock.advanceTimeBy(AmbientMotion.holdMillis(AmbientMotionStatus.Completed) + SETTLE_MILLIS)
        waitForIdle()
        assertEquals(AmbientMotionStatus.Idle, status)
        error = "boom"
        waitForIdle()
        assertEquals(AmbientMotionStatus.Failed, status)
    }

    /**
     * Reduced motion: the glow's tint and envelope snap instead of animating, so the CPU test
     * renderer draws a few shader frames rather than one per animation frame.
     */
    @Composable
    private fun StillTheme(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            MaterialTheme(colorScheme = darkColorScheme(), content = content)
        }
    }

    /** The largest difference between two ARGB pixels in any one channel. */
    private fun channelDistance(a: Int, b: Int): Int =
        (0 until 32 step 8).maxOf { shift -> kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) }

    private companion object {
        const val DITHER_TOLERANCE = 2
        val GLOW_SIZE = 240.dp
        const val SETTLE_MILLIS = 1_000L
        const val STILL_CHECK_MILLIS = 1_500L
    }
}
