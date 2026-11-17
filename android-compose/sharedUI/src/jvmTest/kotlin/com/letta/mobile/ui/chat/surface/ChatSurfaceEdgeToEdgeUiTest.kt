@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.shell.LocalShellFloatingChromeInset
import com.letta.mobile.ui.shell.workspace.underFloatingChrome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost

/**
 * letta-mobile-bglj6.1: the phone page is edge to edge, as the legacy Android chat screen is. The
 * host's status bar and floating header (ChatSurfacePlatform.topChromeInset) sit over the page:
 * the timeline and the canvas draw from the very top, under them, while what rests at the top
 * (the oldest row, the host's overlay, the chat head, the canvas's actions pill) rests below them.
 */
class ChatSurfaceEdgeToEdgeUiTest {
    private val messages = (0 until MESSAGE_COUNT).map { index ->
        UiMessage(
            id = "m$index",
            role = if (index % 2 == 0) "user" else "assistant",
            content = "Message $index of the conversation",
            timestamp = "2026-10-01T10:%02d:00Z".format(index),
            runId = if (index % 2 == 0) null else "run-$index",
        )
    }

    private class Port(messages: List<UiMessage>, active: Boolean = false) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = messages.toPersistentList(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
                runPhase = if (active) RunPhase.REASONING else RunPhase.IDLE,
            ),
        )
        override val composer = MutableStateFlow(
            ChatComposerUiState(canSend = true, model = ChatModelUiState(currentHandle = "m", currentLabel = "Model")),
        )
        override val actions: ChatActions = RecordingChatActions()
    }

    /**
     * The page with the host's chrome as [ChatSurfacePlatform.topChromeInset], or (with [floating])
     * as the shell workspace's floating chrome, which the shell's chat page folds into its platform.
     */
    private fun ComposeUiTest.show(
        presentation: ChatSurfacePresentation,
        floating: Boolean = false,
        active: Boolean = false,
        canvas: (@androidx.compose.runtime.Composable (ChatCanvasActions) -> Unit)? = null,
    ) {
        setContent {
            val platform = ChatSurfacePlatform(
                showKeyboardHints = false,
                pageBackground = if (active) { content ->
                    Box(Modifier.fillMaxSize()) {
                        ChatPanelAmbientGlow(ChatAmbient(AmbientMotionStatus.Running), AmbientGlowPlacement.Halo, Modifier.fillMaxSize())
                        content()
                    }
                } else null,
                timelineOverlay = { Box(Modifier.size(1.dp).testTag(OVERLAY_PROBE)) },
                topChromeInset = if (floating) 0.dp else TOP_CHROME,
            )
            MaterialTheme(colorScheme = if (active) darkColorScheme() else MaterialTheme.colorScheme) {
                CompositionLocalProvider(
                    LocalShellFloatingChromeInset provides if (floating) TOP_CHROME else 0.dp,
                    LocalReducedMotion provides active,
                ) {
                    ProvideLettaHost(LettaHost.Touch) {
                        ChatSurface(
                            port = Port(messages, active),
                            presentation = presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            modifier = Modifier.fillMaxSize(),
                            platform = platform.underFloatingChrome(LocalShellFloatingChromeInset.current),
                            canvas = canvas,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun scrolledActiveTimelineKeepsTextBrightNearItsBottom() = runComposeUiTest {
        show(ChatSurfacePresentation.ChatFirst, active = true)
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(10)
        waitForIdle()
        val list = onNodeWithTag(ChatTimelineTags.LIST).getBoundsInRoot()
        val bottom = with(density) { list.bottom.toPx().toInt() }
        val inset = with(density) { 20.dp.roundToPx() }
        var brightest = 0f
        repeat(8) {
            val pixels = onRoot().captureToImage().toPixelMap()
            val y = (bottom - inset).coerceIn(0, pixels.height - 1)
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                brightest = maxOf(brightest, c.red + c.green + c.blue)
            }
            onNodeWithTag(ChatTimelineTags.LIST).performTouchInput { swipeDown() }
            waitForIdle()
        }
        assertTrue(brightest >= 2.4f, "scrolled text 20dp from list bottom: $brightest")
    }

    @Test
    fun theTimelineScrollsUnderTheChromeAndItsOldestRowRestsBelowIt() = runComposeUiTest {
        show(ChatSurfacePresentation.ChatFirst)
        assertTheTimelineRestsBelowTheChrome()
    }

    /** The phone shell's floating header (pill, search, menu) is chrome the timeline rests below too. */
    @Test
    fun theWorkspacesFloatingChromeRestsTheTimelineBelowIt() = runComposeUiTest {
        show(ChatSurfacePresentation.ChatFirst, floating = true)
        assertTheTimelineRestsBelowTheChrome()
    }

    private fun ComposeUiTest.assertTheTimelineRestsBelowTheChrome() {
        // The list itself reaches the top edge: rows scroll behind the status bar and header.
        assertEquals(0.dp, onNodeWithTag(ChatTimelineTags.LIST).getBoundsInRoot().top)
        // Scrolled all the way back, the oldest row rests below the chrome, not under it.
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToNode(hasText("Message 0 of", substring = true))
        repeat(FLINGS) { onNodeWithTag(ChatTimelineTags.LIST).performTouchInput { swipeDown() } }
        waitForIdle()
        val oldest = onNodeWithText("Message 0 of", substring = true).getBoundsInRoot().top
        assertTrue(oldest >= TOP_CHROME, "the oldest row rests at $oldest, under the ${TOP_CHROME} chrome")
        // The host's overlay (Android's subagent rings) also starts below the chrome.
        assertTrue(onNodeWithTag(OVERLAY_PROBE).getBoundsInRoot().top >= TOP_CHROME)
    }

    @Test
    fun theCanvasRunsUnderTheChromeAndTheChatHeadKeepsBelowIt() = runComposeUiTest {
        show(ChatSurfacePresentation.CanvasFirst) { _ -> Box(Modifier.fillMaxSize().testTag(CANVAS_PROBE)) }

        assertEquals(0.dp, onNodeWithTag(CANVAS_PROBE).getBoundsInRoot().top)
        assertTrue(onNodeWithTag(TOUCH_HEAD_TAG).getBoundsInRoot().top >= TOP_CHROME)
    }

    @Test
    fun theCanvasActionsPillRestsBelowTheHostChrome() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CanvasWorkspace(showTitle = false, layout = CanvasLayout.COMPACT, chromeTopInset = TOP_CHROME)
            }
        }
        waitForIdle()

        // On a phone undo sits in the top pill, which keeps below the header floating over the board.
        assertTrue(onNodeWithContentDescription("Undo").getBoundsInRoot().top >= TOP_CHROME)
    }

    private companion object {
        val TOP_CHROME: Dp = 120.dp
        const val MESSAGE_COUNT = 40
        const val FLINGS = 5
        const val OVERLAY_PROBE = "edge-to-edge-overlay-probe"
        const val CANVAS_PROBE = "edge-to-edge-canvas-probe"
    }
}
