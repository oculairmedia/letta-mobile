@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DockedChatPanelUiTest {
    private class FixturePort : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(
                    UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z"),
                    UiMessage(id = "a1", role = "assistant", content = "Here's an L-shaped plan.", timestamp = "2026-09-30T18:02:09Z"),
                ),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private class Harness {
        var geometry by mutableStateOf(ChatDockGeometry.Default)
        val reported = mutableListOf<ChatDockGeometry>()
        var canvasClicks by mutableIntStateOf(0)
    }

    private fun ComposeUiTest.show(): Harness {
        val harness = Harness()
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = FixturePort(),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    canvas = { _ ->
                        Box(Modifier.fillMaxSize().testTag(CANVAS_TAG).clickable { harness.canvasClicks++ })
                    },
                    dockGeometry = harness.geometry,
                    onDockGeometryChange = {
                        harness.geometry = it
                        harness.reported += it
                    },
                )
            }
        }
        waitForIdle()
        return harness
    }

    @Test
    fun draggingTheHeaderMovesThePanelAndReportsTheGeometry() = runComposeUiTest {
        val harness = show()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { swipe(center, center + Offset(-150f, -120f)) }
        waitForIdle()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(after.left < before.left, "moved left: $before -> $after")
        assertTrue(after.top < before.top, "moved up: $before -> $after")
        assertEquals(before.width, after.width)
        assertTrue(harness.reported.isNotEmpty())
        assertTrue(harness.geometry.anchorX < 0.5f && harness.geometry.anchorY < 1f, "${harness.geometry}")
    }

    @Test
    fun doubleTappingTheHeaderResetsThePlacement() = runComposeUiTest {
        val harness = show()
        val home = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { swipe(center, center + Offset(-150f, -120f)) }
        waitForIdle()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        assertEquals(ChatDockGeometry.Default, harness.geometry)
        assertEquals(home, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot())
    }

    @Test
    fun theResizeGripChangesTheSize() = runComposeUiTest {
        val harness = show()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_RESIZE_GRIP_TAG).performTouchInput { swipe(center, center + Offset(80f, 0f)) }
        waitForIdle()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(after.width > before.width, "wider: $before -> $after")
        assertEquals(before.left, after.left)
        assertTrue(harness.geometry.widthDp != null)
    }

    @Test
    fun collapsingHidesTheConversationAndExpandingRestoresTheSize() = runComposeUiTest {
        val harness = show()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
        onNodeWithTag(DOCK_COLLAPSE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(DOCKED_REPLY_TAG).assertDoesNotExist()
        assertTrue(harness.geometry.collapsed)
        val collapsed = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(collapsed.height < before.height)
        assertEquals(before.bottom, collapsed.bottom)
        onNodeWithTag(DOCK_RESTORE_TAG).performClick()
        waitForIdle()
        assertFalse(harness.geometry.collapsed)
        onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
        assertEquals(before, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot())
    }

    @Test
    fun theCanvasStillReceivesClicksOutsideThePanelButNotInsideIt() = runComposeUiTest {
        val harness = show()
        onNodeWithTag(CANVAS_TAG).performTouchInput { click(Offset(20f, 20f)) }
        waitForIdle()
        assertEquals(1, harness.canvasClicks)
        onNodeWithTag(DOCKED_REPLY_TAG).performTouchInput { click(center) }
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { click(center) }
        waitForIdle()
        assertEquals(1, harness.canvasClicks)
    }

    private companion object {
        const val CANVAS_TAG = "test-canvas"
    }
}
