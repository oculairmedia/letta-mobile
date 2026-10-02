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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
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
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DockedChatPanelUiTest {
    private class FixturePort(composer: ChatComposerUiState = ChatComposerUiState()) : ChatSessionPort {
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
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(composer)
        val recording = RecordingChatActions()
        override val actions: ChatActions = recording
    }

    private class Harness {
        var geometry by mutableStateOf(ChatDockGeometry.Default)
        val reported = mutableListOf<ChatDockGeometry>()
        var canvasClicks by mutableIntStateOf(0)
    }

    private fun ComposeUiTest.show(port: FixturePort = FixturePort(), initial: ChatDockGeometry = ChatDockGeometry.Default): Harness {
        val harness = Harness()
        harness.geometry = initial
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = port,
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
    fun noDockIsDrawnUntilTheSavedPlacementIsKnown() = runComposeUiTest {
        var placement by mutableStateOf<ChatDockGeometry?>(null)
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = FixturePort(),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    canvas = { _ -> Box(Modifier.fillMaxSize().testTag(CANVAS_TAG)) },
                    dockGeometry = placement,
                    onDockGeometryChange = { placement = it },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(CANVAS_TAG).assertExists()
        onNodeWithTag(DOCK_PANEL_TAG).assertDoesNotExist()

        // The saved placement arrives: the panel appears there directly, never at the default first.
        // One frame at a time, so the first frame it is drawn in is the one checked.
        mainClock.autoAdvance = false
        val saved = ChatDockGeometry(anchorX = 0.1f, anchorY = 0.2f, widthDp = 420f, heightDp = 380f)
        placement = saved
        mainClock.advanceTimeByFrame()
        onNodeWithTag(DOCK_PANEL_TAG).assertExists()
        assertEquals(420f, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot().width.value, 1f)
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(420f, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot().width.value, 1f)
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
    fun draggedToTheTopThePanelLeavesRoomForItsBadge() = runComposeUiTest {
        show()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { swipe(center, center + Offset(0f, -5000f)) }
        waitForIdle()
        val top = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot().top
        // The badge's upper half rises above the panel's edge; it stays on the canvas, inside the margin.
        assertEquals((ChatSurfaceDimens.dockMargin + ChatSurfaceDimens.dockBadgeOverhang).value, top.value, 0.5f)
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
    fun aResetThatLeavesThePanelInPlaceDoesNotSlowTheNextDrag() = runComposeUiTest {
        // Not the default, but drawn exactly where the default is: the reset moves nothing.
        show(initial = ChatDockGeometry(widthDp = ChatSurfaceDimens.dockDefaultWidth.value))
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        mainClock.autoAdvance = false
        val step = with(density) { DRAG_STEP_DP.dp.toPx() }
        // Past the touch slop first; then one more step must move the panel by exactly that step.
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-step, 0f))
        }
        mainClock.advanceTimeByFrame()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { moveBy(Offset(-step, 0f)) }
        mainClock.advanceTimeByFrame()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_HEADER_TAG).performTouchInput { up() }
        assertEquals(DRAG_STEP_DP, (before.left - after.left).value, 1f)
    }

    @Test
    fun theTopRightCornerResizesTowardsItself() = runComposeUiTest {
        show()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val corner = ChatSurfaceDimens.dockResizeCorner / 2
        val start = with(density) { Offset((before.right + corner).toPx(), (before.top - corner).toPx()) }
        val reach = with(density) { DRAG_STEP_DP.dp.toPx() }
        onRoot().performTouchInput { swipe(start, start + Offset(reach, -reach)) }
        waitForIdle()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(after.width > before.width, "wider: $before -> $after")
        assertTrue(after.height > before.height, "taller: $before -> $after")
        assertEquals(before.left.value, after.left.value, 0.5f)
        assertEquals(before.bottom.value, after.bottom.value, 0.5f)
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
    fun collapsingLeavesTheMascotOverTheBarAndExpandingRestoresTheSize() = runComposeUiTest {
        val harness = show()
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
        onNodeWithTag(DOCK_COLLAPSE_TAG).performClick()
        waitForIdle()
        // No panel, no header: the mascot and the bar float on the canvas.
        onNodeWithTag(DOCKED_REPLY_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_SURFACE_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_HEADER_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).assertExists()
        onNodeWithTag(ComposerTestTags.DOCKED_BAR).assertExists()
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
    fun theMinimisedDockMovesToTheTopAndOpensDownwardFromThere() = runComposeUiTest {
        val harness = show()
        onNodeWithTag(DOCK_COLLAPSE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).performTouchInput { swipe(center, center + Offset(0f, -5000f)) }
        waitForIdle()
        val page = onRoot().getBoundsInRoot()
        val minimised = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        // Into the upper half: its own top stops at the margin, not the open panel's.
        assertTrue(minimised.bottom < page.bottom / 2, "minimised at $minimised in $page")
        assertEquals(0f, harness.geometry.anchorY)

        // Opening there: no room above, so the panel grows down from the top, on the canvas.
        mainClock.autoAdvance = false
        onNodeWithTag(DOCK_RESTORE_TAG).performClick()
        mainClock.advanceTimeByFrame()
        val firstFrame = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(firstFrame.bottom <= minimised.bottom + 1.dp, "the fold starts at the bar: $minimised -> $firstFrame")
        mainClock.autoAdvance = true
        waitForIdle()
        val open = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertFalse(harness.geometry.collapsed)
        assertTrue(open.top >= page.top && open.bottom <= page.bottom, "open on the canvas: $open in $page")
        assertTrue(open.bottom > minimised.bottom, "grew downward: $minimised -> $open")
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

    @Test
    fun theResizeHandlesNeverCoverTheSendButtonAtThePanelsEdge() = runComposeUiTest {
        val port = FixturePort(ChatComposerUiState(text = "Add an island", canSend = true))
        show(port)
        // The outermost pixel of the send button, where an edge strip inside the panel would sit.
        onNodeWithTag(ComposerTestTags.SEND).performTouchInput { click(Offset(width - 1f, centerY)) }
        waitForIdle()
        assertEquals(1, port.recording.count("send"))
    }

    @Test
    fun theDockOffersPlacementActionsToAssistiveTechnology() = runComposeUiTest {
        val harness = show()
        val actions = onNodeWithTag(DOCK_PANEL_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        fun run(label: String) = runOnIdle { actions.single { it.label == label }.action() }

        run("Move the chat up")
        waitForIdle()
        assertTrue(harness.geometry.anchorY < 1f, "")

        run("Make the chat bigger")
        waitForIdle()
        assertTrue(harness.geometry.widthDp != null && harness.geometry.heightDp != null, "")

        run("Put the chat back in its place")
        waitForIdle()
        assertEquals(ChatDockGeometry.Default, harness.geometry)

        run("Minimise the chat to its bar")
        waitForIdle()
        assertTrue(harness.geometry.collapsed)

        // Minimised, the same node offers the way back.
        val collapsedActions = onNodeWithTag(DOCK_PANEL_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnIdle { collapsedActions.single { it.label == "Show the conversation" }.action() }
        waitForIdle()
        assertFalse(harness.geometry.collapsed)
    }

    private companion object {
        const val CANVAS_TAG = "test-canvas"
        const val DRAG_STEP_DP = 60f
    }
}
