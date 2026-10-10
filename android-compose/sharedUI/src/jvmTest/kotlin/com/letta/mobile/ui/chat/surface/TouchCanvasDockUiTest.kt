@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
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
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.PointerObservingMascotHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1.9: the Touch idiom. On the canvas there is no panel and (letta-mobile-y5q9z)
 * no bar: a chat head the reply pops out of, which opens the canvas bubble's card (see
 * CanvasBubbleUiTest). Without a canvas the docked head keeps its bar: the head toggles the popup and
 * the popup opens the chat. A drag snaps the head to the nearer edge and reports its place. The full
 * page draws the phone bar: no card, no model chip, no keyboard hints.
 */
class TouchCanvasDockUiTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z")
    private val reply = UiMessage(id = "a1", role = "assistant", content = "Here's an L-shaped plan.", timestamp = "2026-09-30T18:02:09Z", runId = "run-1")
    private val question = UiMessage(
        id = "q1",
        role = "assistant",
        content = "",
        timestamp = "2026-09-30T18:02:10Z",
        runId = "run-1",
        approvalRequest = UiApprovalRequest(
            requestId = "req-1",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "ask-1",
                    name = "AskUserQuestion",
                    arguments = """{"questions":[{"question":"Which layout?","options":[{"label":"Island"},{"label":"Galley"}]}]}""",
                ),
            ),
        ),
    )

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
        var agentPaneOpened = 0
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
    fun theCanvasHasAHeadButNoBarAndNoPanel() = runComposeUiTest {
        show(Port(), ChatSurfacePresentation.CanvasFirst)
        onAllNodesWithTag(ComposerTestTags.TOUCH_BAR).assertCountEquals(0)
        onNodeWithTag(TOUCH_HEAD_TAG).assertExists()
        onAllNodesWithTag(DOCK_PANEL_TAG).assertCountEquals(0)
        onAllNodesWithTag(ComposerTestTags.EXPAND).assertCountEquals(0)
    }

    @Test
    fun withoutACanvasTheBarsChevronOpensTheChat() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst, withCanvas = false)
        onNodeWithTag(ComposerTestTags.TOUCH_RESTORE).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), harness.intents)
    }

    @Test
    fun withoutACanvasTheReplyPopsOutOfTheHeadWhichTogglesIt() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, reply))
        val harness = show(port, ChatSurfacePresentation.CanvasFirst, withCanvas = false)
        onNodeWithTag(TOUCH_POPUP_TAG).assertExists()
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(TOUCH_POPUP_TAG).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), harness.intents)
    }

    /** letta-mobile-bglj6.1.22: the canvas answers a question without opening the full page. */
    @Test
    fun aPendingQuestionIsAnsweredOnTheCanvas() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, question))
        val harness = show(port, ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(TOUCH_INPUT_TRAY_TAG).assertExists()
        onNodeWithText("Island").performClick()
        onNodeWithText("Send answer").performClick()
        waitForIdle()
        val answer = (port.actions as RecordingChatActions).approvals.single()
        assertEquals("req-1", answer.requestId)
        assertTrue(answer.approve)
        assertTrue(answer.reason.orEmpty().contains("Island"))
        assertTrue(harness.intents.isEmpty(), "answering opened the chat: ${harness.intents}")
    }

    @Test
    fun anAnsweredQuestionLeavesTheCanvasClear() = runComposeUiTest {
        val port = Port()
        val answered = UiMessage(
            id = "r1",
            role = "user",
            content = "",
            timestamp = "2026-09-30T18:02:20Z",
            approvalResponse = UiApprovalResponse(requestId = "req-1", approved = true),
        )
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, question, answered))
        show(port, ChatSurfacePresentation.CanvasFirst)
        onAllNodesWithTag(TOUCH_INPUT_TRAY_TAG).assertCountEquals(0)
    }

    /** letta-mobile-bglj6.1.22: the host's canvas chrome (subagent rings) shows on the canvas, not on the page. */
    @Test
    fun theHostsCanvasOverlayShowsOnlyOnTheCanvas() = runComposeUiTest {
        var presentation by mutableStateOf(ChatSurfacePresentation.CanvasFirst)
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = presentation,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(
                        showKeyboardHints = false,
                        canvasOverlay = { Box(Modifier.fillMaxSize().testTag(RINGS_TAG)) },
                    ),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(RINGS_TAG).assertExists()
        val rings = onNodeWithTag(ChatSurfaceTags.CANVAS_OVERLAY).getBoundsInRoot()
        val board = onNodeWithTag(TOUCH_CANVAS_TAG).getBoundsInRoot()
        assertTrue(rings.bottom <= board.bottom, "the overlay runs past the board: $rings vs $board")
        presentation = ChatSurfacePresentation.ChatFirst
        waitForIdle()
        onAllNodesWithTag(RINGS_TAG).assertCountEquals(0)
    }

    @Test
    fun withoutACanvasAndNothingToShowTheHeadOpensTheChat() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst, withCanvas = false)
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
    fun aSecondDragSnapsFromWhereTheFirstLeftTheHead() = runComposeUiTest {
        val harness = show(Port(), ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x - DRAG_PX, center.y), durationMillis = 300)
        }
        waitForIdle()
        assertEquals(0f, harness.geometries.last().anchorX)
        // A nudge on the left: the head stays there, not back at the side it rested on at first.
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x + NUDGE_PX, center.y), durationMillis = 200)
        }
        waitForIdle()
        assertEquals(0f, harness.geometries.last().anchorX)
        val head = onNodeWithTag(TOUCH_HEAD_TAG).fetchSemanticsNode().boundsInRoot
        val page = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(head.center.x < page.center.x, "the head went back to the right: $head")
    }

    @Test
    fun aLongPressOpensTheAgentPaneOnceTheHostOffersIt() = runComposeUiTest {
        var host by mutableStateOf(ChatSurfaceHost(openCanvas = {}))
        var opened = 0
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = host,
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()
        host = ChatSurfaceHost(openCanvas = {}, openAgentPane = { opened++ })
        waitForIdle()
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput { longClick() }
        waitForIdle()
        assertEquals(1, opened)
    }

    /**
     * As the Android app draws the page: the window's mascot shell knows the agent, and no
     * transport layer is mounted, so the page's one seat draws the character itself.
     */
    private fun ComposeUiTest.showWithoutALayer(shell: FakeMascotShell): Harness {
        val harness = Harness()
        // The live mascot keeps a frame loop running, so the page never idles: step the clock.
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MaterialTheme {
                    ChatSurface(
                        port = Port(),
                        presentation = ChatSurfacePresentation.CanvasFirst,
                        onIntent = { harness.intents += it },
                        host = ChatSurfaceHost(openCanvas = {}, openAgentPane = { harness.agentPaneOpened++ }),
                        modifier = Modifier.fillMaxSize(),
                        appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                        platform = ChatSurfacePlatform(showKeyboardHints = false),
                        canvas = { _ -> Box(Modifier.fillMaxSize()) },
                        onDockGeometryChange = { harness.geometries += it },
                    )
                }
            }
        }
        settle()
        return harness
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
    }

    /**
     * The Android composition: no transport layer, so the page's one seat draws the live mascot
     * over the head itself, and the renderer carries Rive's pointer filter (it watches, consumes
     * nothing). A hit on it once ended the page's hit test at the seat's overlay, so the head never
     * saw its drag, tap or long press; with the fake renderer drawing no input this could not show.
     */
    @Test
    fun underAndroidsSeatTheHeadStillDragsSnapsAndRemembers() = runComposeUiTest {
        val harness = showWithoutALayer(FakeMascotShell("agent-1", layerMounted = false, host = PointerObservingMascotHost))
        onAllNodesWithTag(FakeMascotHost.SURFACE_TAG, useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x - DRAG_PX, center.y - DRAG_PX / 2), durationMillis = 300)
        }
        settle()
        val placed = harness.geometries.last()
        assertEquals(0f, placed.anchorX, "the head snapped to the nearer (left) edge")
        assertTrue(placed.anchorY < 1f, "the head kept the height it was dropped at: $placed")
        val head = onNodeWithTag(TOUCH_HEAD_TAG).fetchSemanticsNode().boundsInRoot
        val page = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(head.center.x < page.center.x, "the head did not move: $head")
    }

    @Test
    fun underAndroidsSeatTheHeadStillTakesTapAndLongPress() = runComposeUiTest {
        val harness = showWithoutALayer(FakeMascotShell("agent-1", layerMounted = false, host = PointerObservingMascotHost))
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput { click() }
        settle()
        // On the canvas a tap opens the bubble's card, not the full chat.
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
        assertTrue(harness.intents.isEmpty(), "the head opened the full chat: ${harness.intents}")
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput { click() }
        settle()
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput { longClick() }
        settle()
        assertEquals(1, harness.agentPaneOpened)
    }

    @Test
    fun withoutAMascotLayerTheHeadDrawsTheAgentsMascot() = runComposeUiTest {
        showWithoutALayer(FakeMascotShell("agent-1", layerMounted = false))
        val surfaces = onAllNodesWithTag(FakeMascotHost.SURFACE_TAG, useUnmergedTree = true)
        surfaces.assertCountEquals(1)
        // The character stands on the head, in place of the sphere.
        val head = onNodeWithTag(TOUCH_HEAD_TAG).fetchSemanticsNode().boundsInRoot
        val mascot = surfaces[0].fetchSemanticsNode().boundsInRoot
        assertTrue(head.contains(mascot.center), "the mascot is not on the head: $mascot vs $head")
    }

    @Test
    fun anAgentWithoutAMascotKeepsTheSphere() = runComposeUiTest {
        showWithoutALayer(FakeMascotShell("another-agent", layerMounted = false))
        onNodeWithTag(TOUCH_HEAD_TAG).assertExists()
        onAllNodesWithTag(FakeMascotHost.SURFACE_TAG, useUnmergedTree = true).assertCountEquals(0)
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

    @Test
    fun theBoardRunsToTheFootAndTheCardsBarIsThePagesBar() = runComposeUiTest {
        val port = Port()
        port.composer.value = port.composer.value.copy(text = "Make the island longer")
        var presentation by mutableStateOf(ChatSurfacePresentation.CanvasFirst)
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = port,
                    presentation = presentation,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()
        val root = onRoot().getBoundsInRoot()
        // No bar on the canvas: the board runs to the screen's foot.
        val board = onNodeWithTag(TOUCH_CANVAS_TAG).getBoundsInRoot()
        assertEquals(root.bottom.value, board.bottom.value, DP_TOLERANCE)
        // The bubble's card carries the page's own bar, with the same draft.
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        val cardBar = onNodeWithTag(ComposerTestTags.TOUCH_BAR).getBoundsInRoot()
        onNodeWithText("Make the island longer").assertExists()

        presentation = ChatSurfacePresentation.ChatFirst
        waitForIdle()
        onAllNodesWithTag(ComposerTestTags.TOUCH_BAR).assertCountEquals(1)
        val pageBar = onNodeWithTag(ComposerTestTags.TOUCH_BAR).getBoundsInRoot()
        assertEquals((cardBar.bottom - cardBar.top).value, (pageBar.bottom - pageBar.top).value, DP_TOLERANCE)
        assertEquals(root.bottom, pageBar.bottom)
    }

    private companion object {
        const val DP_TOLERANCE = 0.5f
        const val DRAG_PX = 700f
        const val NUDGE_PX = 40f
        const val SETTLE_MILLIS = 2_000L
        const val RINGS_TAG = "test-canvas-rings"
    }
}
