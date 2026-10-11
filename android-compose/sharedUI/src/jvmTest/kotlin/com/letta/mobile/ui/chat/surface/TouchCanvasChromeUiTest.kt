@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CANVAS_ACTIONS_TAG
import com.letta.mobile.ui.canvas.CANVAS_COMPACT_TOOLBAR_TAG
import com.letta.mobile.ui.canvas.CANVAS_FOOT_MORE_TAG
import com.letta.mobile.ui.canvas.CanvasInsert
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.surface.ambient.CHAT_AMBIENT_GLOW_TAG
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.theme.ChatHeadDimens
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1: the phone's canvas mode keeps the top of the board clear. No actions pill:
 * undo, redo and the more menu end the tool bar at the foot, share and the sync status are in that
 * menu, and so are the agent switcher and agent menu the host's header carries elsewhere. The
 * chat head has no glow. A desktop (Pointer) board keeps its pill.
 */
class TouchCanvasChromeUiTest {
    private class Port(typing: Boolean = false) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(),
                isLoadingMessages = false,
                isAgentTyping = typing,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer = MutableStateFlow(
            ChatComposerUiState(canSend = true, model = ChatModelUiState(currentHandle = "m", currentLabel = "Model")),
        )
        override val actions = RecordingChatActions()
    }

    private class Host {
        var switched = 0
        var menus = 0
        val host = ChatSurfaceHost(openCanvas = {}, openAgentSwitcher = { switched++ }, openAgentPane = { menus++ })
    }

    private fun ComposeUiTest.showBoard(
        style: ChatPlatformStyle,
        controller: DrawBoxController,
        host: Host = Host(),
        presentation: ChatSurfacePresentation = ChatSurfacePresentation.CanvasFirst,
    ) {
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = presentation,
                    onIntent = {},
                    host = host.host,
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = style),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ ->
                        CanvasWorkspace(
                            controller = controller,
                            showTitle = false,
                            layout = CanvasLayout.COMPACT,
                            onShareToChat = { _, _ -> },
                        )
                    },
                    dockGeometry = ChatDockGeometry.Default,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun theTouchCanvasHasNoTopPillAndItsActionsEndTheToolBar() = runComposeUiTest {
        showBoard(ChatPlatformStyle.Touch, DrawBoxController(Reducer(UseCase())))

        onAllNodesWithTag(CANVAS_ACTIONS_TAG).assertCountEquals(0)
        val bar = onNodeWithTag(CANVAS_COMPACT_TOOLBAR_TAG).getBoundsInRoot()
        for (label in listOf("Undo", "Redo")) {
            onAllNodesWithContentDescription(label).assertCountEquals(1)
            val button = onNodeWithContentDescription(label).getBoundsInRoot()
            assertTrue(button.top >= bar.top && button.bottom <= bar.bottom, "$label is not on the tool bar: $button vs $bar")
        }
        val more = onNodeWithTag(CANVAS_FOOT_MORE_TAG).getBoundsInRoot()
        assertTrue(more.top >= bar.top && more.bottom <= bar.bottom, "the more button is not on the tool bar: $more vs $bar")
        // Nothing of the board's sits in the top band any more.
        assertTrue(bar.top > onRoot().getBoundsInRoot().bottom / 2, "the tool bar left the foot: $bar")
    }

    @Test
    fun undoAndRedoOnTheToolBarWork() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        showBoard(ChatPlatformStyle.Touch, controller)
        runOnIdle { CanvasInsert.addShape(controller, Mode.RECTANGLE, Offset(200f, 200f)) }
        waitUntil(timeoutMillis = 5_000) { controller.state.value.elements.size == 1 }

        onNodeWithContentDescription("Undo").performClick()
        waitUntil(timeoutMillis = 5_000) { controller.state.value.elements.isEmpty() }
        onNodeWithContentDescription("Redo").performClick()
        waitUntil(timeoutMillis = 5_000) { controller.state.value.elements.size == 1 }
    }

    @Test
    fun theMoreMenuCarriesShareTheAgentSwitcherAndTheAgentMenu() = runComposeUiTest {
        val host = Host()
        showBoard(ChatPlatformStyle.Touch, DrawBoxController(Reducer(UseCase())), host)

        onNodeWithTag(CANVAS_FOOT_MORE_TAG).performClick()
        waitForIdle()
        onNodeWithText("Share to chat").assertExists()
        onNodeWithText("Switch agent").performClick()
        waitForIdle()
        assertEquals(1, host.switched)

        onNodeWithTag(CANVAS_FOOT_MORE_TAG).performClick()
        waitForIdle()
        onNodeWithText("Agent menu").performClick()
        waitForIdle()
        assertEquals(1, host.menus)
    }

    @Test
    fun aDesktopBoardKeepsItsActionsPill() = runComposeUiTest {
        showBoard(ChatPlatformStyle.Pointer, DrawBoxController(Reducer(UseCase())))

        onNodeWithTag(CANVAS_ACTIONS_TAG).assertExists()
        onAllNodesWithTag(CANVAS_FOOT_MORE_TAG).assertCountEquals(0)
        val pill = onNodeWithTag(CANVAS_ACTIONS_TAG).getBoundsInRoot()
        val undo = onNodeWithContentDescription("Undo").getBoundsInRoot()
        assertTrue(undo.top >= pill.top && undo.bottom <= pill.bottom, "undo left the pill: $undo vs $pill")
    }

    @Test
    fun aBoardWithoutAHostPageKeepsItsActionsPill() = runComposeUiTest {
        setContent { MaterialTheme { CanvasWorkspace(showTitle = false, layout = CanvasLayout.COMPACT) } }
        waitForIdle()
        onNodeWithTag(CANVAS_ACTIONS_TAG).assertExists()
        onAllNodesWithTag(CANVAS_FOOT_MORE_TAG).assertCountEquals(0)
    }

    @Test
    fun theChatHeadHasNoGlowWhileTheAgentWorks() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ChatSurface(
                    port = Port(typing = true),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ -> Box(Modifier.fillMaxSize().background(Color.White)) },
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true).assertCountEquals(0)
        // Beside the head, past its shadow, is the board itself: no halo tints it.
        val head = onNodeWithTag(TOUCH_HEAD_TAG).getBoundsInRoot()
        val pixels = onRoot().captureToImage().toPixelMap()
        val probe = with(density) {
            Offset((head.left - ChatHeadDimens.head / 4).toPx(), ((head.top + head.bottom) / 2).toPx())
        }
        assertEquals(Color.White, pixels[probe.x.toInt(), probe.y.toInt()], "the head glows at $probe")
        // letta-mobile-y5q9z: with no bar on the canvas, the bubble says the agent is working (and offers Stop).
        onNodeWithTag(BUBBLE_STOP_TAG).assertExists()
    }
}
