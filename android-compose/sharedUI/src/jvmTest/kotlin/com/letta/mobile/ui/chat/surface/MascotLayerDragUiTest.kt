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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotTransportLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1: the page's companion under a REAL mascot transport layer, which draws the
 * character above every seat, so a drag on the character reaches the layer, not the seat. Seated
 * on the dock (the open panel's badge, the minimised dock) it moves the dock; on the full page it
 * moves nothing and takes nothing; over the Touch chat head it leaves the head its own gestures.
 */
class MascotLayerDragUiTest {
    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(
                    UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z"),
                    UiMessage(id = "a1", role = "assistant", content = "Here's an L-shaped plan.", timestamp = "2026-09-30T18:02:09Z", runId = "run-1"),
                ),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    private class Harness(initial: ChatDockGeometry) {
        var geometry by mutableStateOf(initial)
        val reported = mutableListOf<ChatDockGeometry>()
    }

    private fun ComposeUiTest.show(
        presentation: ChatSurfacePresentation,
        initial: ChatDockGeometry = ChatDockGeometry.Default,
        style: ChatPlatformStyle = ChatPlatformStyle.Pointer,
    ): Harness {
        val harness = Harness(initial)
        // The shell starts without a layer; mounting the real one below marks it mounted.
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MascotTransportLayer(reducedMotion = true) {
                    MaterialTheme {
                        ChatSurface(
                            port = Port(),
                            presentation = presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            modifier = Modifier.fillMaxSize(),
                            appearance = ChatSurfaceAppearance(platformStyle = style),
                            platform = ChatSurfacePlatform(showKeyboardHints = false),
                            canvas = { _ -> Box(Modifier.fillMaxSize()) },
                            dockGeometry = harness.geometry,
                            onDockGeometryChange = {
                                harness.geometry = it
                                harness.reported += it
                            },
                        )
                    }
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

    /** Drags the character the layer draws: the only one on screen. */
    private fun ComposeUiTest.dragTheCharacter() {
        onAllNodesWithTag(FakeMascotHost.SURFACE_TAG).assertSingleCharacter()
        onAllNodesWithTag(FakeMascotHost.SURFACE_TAG)[0].performTouchInput { swipe(center, center + Offset(-DRAG_PX, -DRAG_PX)) }
        settle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertSingleCharacter() {
        assertEquals(1, fetchSemanticsNodes().size, "the layer draws the one character")
    }

    @Test
    fun theBadgesCharacterMovesTheOpenDock() = runComposeUiTest {
        val harness = show(ChatSurfacePresentation.CanvasFirst)
        dragTheCharacter()
        assertTrue(harness.reported.isNotEmpty(), "the drag moved the dock")
        assertTrue(harness.geometry.anchorX < 0.5f, "${harness.geometry}")
    }

    @Test
    fun theMinimisedDocksCharacterMovesTheDock() = runComposeUiTest {
        val harness = show(ChatSurfacePresentation.CanvasFirst, ChatDockGeometry(collapsed = true))
        dragTheCharacter()
        assertTrue(harness.reported.isNotEmpty(), "the drag moved the dock")
        assertTrue(harness.geometry.collapsed)
        assertTrue(harness.geometry.anchorX < 0.5f && harness.geometry.anchorY < 1f, "${harness.geometry}")
    }

    @Test
    fun onTheFullPageTheCharacterMovesNothing() = runComposeUiTest {
        val harness = show(ChatSurfacePresentation.ChatFirst)
        dragTheCharacter()
        assertEquals(emptyList(), harness.reported)
    }

    @Test
    fun overTheTouchHeadTheHeadKeepsItsDrag() = runComposeUiTest {
        val harness = show(ChatSurfacePresentation.CanvasFirst, style = ChatPlatformStyle.Touch)
        // The character stands over the head; the drag lands on the head through the layer.
        onNodeWithTag(TOUCH_HEAD_TAG).performTouchInput { swipe(center, center + Offset(-HEAD_DRAG_PX, 0f)) }
        settle()
        assertTrue(harness.reported.isNotEmpty(), "the head snapped and reported its place")
        assertEquals(0f, harness.geometry.anchorX)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val SETTLE_MILLIS = 2_000L
        const val DRAG_PX = 120f
        const val HEAD_DRAG_PX = 700f
    }
}
