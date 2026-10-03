@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow

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

    private class Port(messages: List<UiMessage>) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = messages.toPersistentList(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer = MutableStateFlow(
            ChatComposerUiState(canSend = true, model = ChatModelUiState(currentHandle = "m", currentLabel = "Model")),
        )
        override val actions: ChatActions = RecordingChatActions()
    }

    private fun ComposeUiTest.show(
        presentation: ChatSurfacePresentation,
        canvas: (@androidx.compose.runtime.Composable (ChatCanvasActions) -> Unit)? = null,
    ) {
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(messages),
                    presentation = presentation,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(
                        showKeyboardHints = false,
                        timelineOverlay = { Box(Modifier.size(1.dp).testTag(OVERLAY_PROBE)) },
                        topChromeInset = TOP_CHROME,
                    ),
                    canvas = canvas,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun theTimelineScrollsUnderTheChromeAndItsOldestRowRestsBelowIt() = runComposeUiTest {
        show(ChatSurfacePresentation.ChatFirst)

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
