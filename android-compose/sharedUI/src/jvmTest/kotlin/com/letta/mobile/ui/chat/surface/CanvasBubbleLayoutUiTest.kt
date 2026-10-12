@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutPrefs
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.mascot.FakeMascotShell
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-zxk2n.29: the minimised chat follows the person's layout. The "Bubble layout" sliders
 * size the mascot and the bar, the bar's edges resize it about its centre, and a settled reply's
 * bubble goes on its own after its hold (not while the pointer is over it). The clock is driven by hand.
 */
class CanvasBubbleLayoutUiTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z")
    private val reply = UiMessage(
        id = "a1",
        role = "assistant",
        content = "Here's an L-shaped plan.",
        timestamp = "2026-09-30T18:02:09Z",
        runId = "run-1",
    )

    private class Port(messages: List<UiMessage>) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = messages.toPersistentList(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private fun ComposeUiTest.show(prefs: CanvasBubbleLayoutPrefs, messages: List<UiMessage> = listOf(prompt, reply)) {
        var geometry by mutableStateOf(ChatDockGeometry(collapsed = true))
        mainClock.autoAdvance = false
        setContent {
            FakeMascotShell(AGENT).Provide {
                MaterialTheme {
                    CompositionLocalProvider(LocalCanvasBubbleLayoutPrefs provides prefs) {
                        ChatSurface(
                            port = Port(messages),
                            presentation = ChatSurfacePresentation.CanvasFirst,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            canvas = { _ -> Box(Modifier.fillMaxSize()) },
                            dockGeometry = geometry,
                            onDockGeometryChange = { geometry = it },
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle(millis: Long = SETTLE_MILLIS) {
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private fun ComposeUiTest.barWidthDp(): Float = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot().let { (it.right - it.left).value }

    private fun ComposeUiTest.openMenu() {
        onNodeWithTag(BUBBLE_LAYOUT_BUTTON_TAG).performClick()
        settle()
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1.5f, "$what: expected $expected, was $actual")
    }

    @Test
    fun theDesktopDefaultsDrawASmallMascotOverANarrowBar() = runComposeUiTest {
        show(CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop))
        assertNear(640f, barWidthDp(), "bar")
        val mascot = onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).getBoundsInRoot()
        assertNear(72f, (mascot.right - mascot.left).value, "mascot")
    }

    @Test
    fun theWidthSliderSetsTheBarsRenderedWidthAndThePrefs() = runComposeUiTest {
        val prefs = CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop)
        show(prefs)
        openMenu()
        onNodeWithTag(BUBBLE_LAYOUT_WIDTH_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(480f) }
        settle()
        assertNear(480f, prefs.layout.value.composerWidthDp ?: 0f, "prefs")
        assertNear(480f, barWidthDp(), "bar")
    }

    @Test
    fun theMascotSliderSizesTheMascotAndResetRestoresIt() = runComposeUiTest {
        val prefs = CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop)
        show(prefs)
        openMenu()
        onNodeWithTag(BUBBLE_LAYOUT_MASCOT_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        settle()
        var mascot = onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).getBoundsInRoot()
        assertNear(150f, (mascot.right - mascot.left).value, "mascot")
        onNodeWithTag(BUBBLE_LAYOUT_RESET_TAG).performClick()
        settle()
        mascot = onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).getBoundsInRoot()
        assertNear(72f, (mascot.right - mascot.left).value, "mascot after reset")
        assertEquals(CanvasBubbleLayout.Desktop, prefs.layout.value)
    }

    @Test
    fun draggingTheBarsRightEdgeResizesItAboutItsCentre() = runComposeUiTest {
        val prefs = CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop)
        show(prefs)
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val travelPx = 40f * density.density
        onNodeWithTag(BUBBLE_WIDTH_HANDLE_RIGHT_TAG, useUnmergedTree = true).performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(travelPx / 2f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(travelPx / 2f, 0f))
            release()
        }
        settle()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val grown = (after.right - after.left).value - (before.right - before.left).value
        // Past the drag's touch slop the bar grew by twice the travel, the same on both sides.
        assertTrue(grown > 0f && grown <= 80f + 1.5f, "grew $grown")
        assertNear(((before.left + before.right) / 2).value, ((after.left + after.right) / 2).value, "centre")
    }

    @Test
    fun aSettledReplyGoesOnItsOwnAfterItsHold() = runComposeUiTest {
        show(CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop.copy(replyHoldSeconds = 5f)))
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertExists()
        // Two seconds settled already; two more and it is still up.
        settle(2_000L)
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertExists()
        // Past five seconds, and its fade, it has gone.
        settle(2_000L)
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertDoesNotExist()
    }

    @Test
    fun theReplyStaysWhileThePointerIsOverIt() = runComposeUiTest {
        show(CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop.copy(replyHoldSeconds = 5f)))
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).performMouseInput { moveTo(center) }
        settle(10_000L)
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertExists()
    }

    @Test
    fun alwaysKeepsTheReplyUntilDismissed() = runComposeUiTest {
        show(CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop.copy(replyHoldSeconds = CanvasBubbleLayout.REPLY_HOLD_ALWAYS_S)))
        settle(60_000L)
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertExists()
    }

    private companion object {
        const val AGENT = "agent-1"
        const val SETTLE_MILLIS = 2_000L
    }
}
