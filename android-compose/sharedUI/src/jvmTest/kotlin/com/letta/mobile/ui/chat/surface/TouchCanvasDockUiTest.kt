@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1.9: the Touch idiom. On the canvas there is no panel: a flush bar at the
 * bottom and a chat head the reply pops out of; the head toggles the popup, the popup opens the
 * chat, a drag snaps the head to the nearer edge and reports its place. The full page draws the
 * phone bar: no card, no model chip, no keyboard hints.
 */
class TouchCanvasDockUiTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z")
    private val reply = UiMessage(id = "a1", role = "assistant", content = "Here's an L-shaped plan.", timestamp = "2026-09-30T18:02:09Z", runId = "run-1")

    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(),
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

    private class Harness {
        val intents = mutableListOf<ChatSurfaceIntent>()
        val geometries = mutableListOf<ChatDockGeometry>()
    }

    private fun ComposeUiTest.show(port: Port, presentation: ChatSurfacePresentation, withCanvas: Boolean = true): Harness {
        val harness = Harness()
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = port,
                    presentation = presentation,
                    onIntent = { harness.intents += it },
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = if (withCanvas) ({ _ -> Box(Modifier.fillMaxSize()) }) else null,
                    onDockGeometryChange = { harness.geometries += it },
                )
            }
        }
        waitForIdle()
        return harness
    }

    @Test
    fun theCanvasHasABottomBarAndAHeadButNoPanel() = runComposeUiTest {
        show(Port(), ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).assertExists()
        onNodeWithTag(TOUCH_HEAD_TAG).assertExists()
        onAllNodesWithTag(DOCK_PANEL_TAG).assertCountEquals(0)
        onAllNodesWithTag(ComposerTestTags.EXPAND).assertCountEquals(0)
    }

    @Test
    fun theBarsChevronOpensTheChat() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(ComposerTestTags.TOUCH_RESTORE).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), harness.intents)
    }

    @Test
    fun theReplyPopsOutOfTheHeadWhichTogglesIt() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, reply))
        val harness = show(port, ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(TOUCH_POPUP_TAG).assertExists()
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(TOUCH_POPUP_TAG).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), harness.intents)
    }

    @Test
    fun withNothingToShowTheHeadOpensTheChat() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), harness.intents)
    }

    @Test
    fun aDragSnapsTheHeadToTheNearerEdge() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst)
        // The head starts on the right; drag it most of the way across.
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x - DRAG_PX, center.y - DRAG_PX / 2), durationMillis = 300)
        }
        waitForIdle()
        val placed = harness.geometries.last()
        assertEquals(0f, placed.anchorX)
        assertTrue(placed.anchorY < 1f)
    }

    @Test
    fun theFullPageDrawsThePhoneBar() = runComposeUiTest {
        show(Port(), ChatSurfacePresentation.ChatFirst, withCanvas = false)
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).assertExists()
        onNodeWithTag(ComposerTestTags.TOUCH_PLUS).assertExists()
        onAllNodesWithTag(ComposerTestTags.CARD).assertCountEquals(0)
        onAllNodesWithTag(ComposerTestTags.MODEL_CHIP).assertCountEquals(0)
        onAllNodesWithTag(ComposerTestTags.HINT).assertCountEquals(0)
    }

    private companion object {
        const val DRAG_PX = 700f
    }
}
