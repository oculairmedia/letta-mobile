@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.CompanionTestPort
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlinx.collections.immutable.toPersistentList

/**
 * letta-mobile-ckmub.1 (hard cut S4): the chat page renders in LettaPageScaffold, which owns the
 * keyboard inset once; the composer lifts by exactly the keyboard and, with the keyboard down, runs
 * flush to the page's foot (the bar is drawn under the navigation bar, bglj6.1.9). Fake insets stand
 * in for a phone's keyboard.
 */
class ChatSurfaceInsetsTest {
    private fun ComposeUiTest.page(keyboard: Dp) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalReducedMotion provides true,
                LocalChatSurfaceImeInsets provides WindowInsets(0.dp, 0.dp, 0.dp, keyboard),
            ) {
                MaterialTheme {
                    Box(Modifier.size(WIDTH, HEIGHT)) {
                        ProvideLettaHost(LettaHost.Touch) {
                            ChatSurface(
                                port = CompanionTestPort(typing = false),
                                presentation = ChatSurfacePresentation.ChatFirst,
                                onIntent = {},
                                host = ChatSurfaceHost(),
                                modifier = Modifier.fillMaxSize(),
                                platform = ChatSurfacePlatform(showKeyboardHints = false),
                            )
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    @Test
    fun composerRunsFlushToThePageFootWithTheKeyboardDown() = runComposeUiTest {
        page(keyboard = 0.dp)
        assertEquals(HEIGHT, barBottom())
    }

    @Test
    fun composerLiftsByExactlyTheKeyboard() = runComposeUiTest {
        page(keyboard = KEYBOARD)
        assertEquals(HEIGHT - KEYBOARD, barBottom())
    }

    @Test
    fun touchCanvasCardKeepsItsReplyAndComposerAboveTheKeyboard() = runComposeUiTest {
        canvasDockKeyboardCycle(LettaHost.Touch, BUBBLE_CARD_TAG, ComposerTestTags.TOUCH_BAR)
    }

    @Test
    fun pointerCanvasPanelKeepsItsReplyAndComposerAboveTheKeyboard() = runComposeUiTest {
        canvasDockKeyboardCycle(LettaHost.Pointer, DOCK_PANEL_TAG, ComposerTestTags.DOCKED_BAR)
    }

    private fun ComposeUiTest.canvasDockKeyboardCycle(host: LettaHost, panelTag: String, barTag: String) {
        val keyboard = mutableStateOf(0.dp)
        canvasDock(host, keyboard)
        if (host == LettaHost.Touch) {
            onNodeWithTag(TOUCH_HEAD_TAG).performClick()
            mainClock.advanceTimeBy(SETTLE_MILLIS)
        }
        val panelBefore = onNodeWithTag(panelTag).getBoundsInRoot()
        assertDockContentVisible(panelTag, barTag, keyboard.value)
        onNodeWithTag(ComposerTestTags.DOCKED_INPUT).performClick()
        runOnIdle { keyboard.value = KEYBOARD }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertDockContentVisible(panelTag, barTag, keyboard.value)
        runOnIdle { keyboard.value = 0.dp }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertDockContentVisible(panelTag, barTag, keyboard.value)
        assertEquals(panelBefore, onNodeWithTag(panelTag).getBoundsInRoot(), "keyboard dismissal restores the dock geometry")
    }

    private fun ComposeUiTest.canvasDock(host: LettaHost, keyboard: State<Dp>) {
        val port = CompanionTestPort(typing = false)
        port.uiState.value = port.uiState.value.copy(
            messages = (port.uiState.value.messages +
                UiMessage(id = "a1", role = "assistant", content = REPLY, timestamp = "2026-10-06T16:00:09Z", runId = "run-1")
            ).toPersistentList(),
        )
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalReducedMotion provides true,
                LocalChatSurfaceImeInsets provides WindowInsets(0.dp, 0.dp, 0.dp, keyboard.value),
            ) {
                MaterialTheme {
                    Box(Modifier.size(WIDTH, HEIGHT)) {
                        ProvideLettaHost(host) {
                            ChatSurface(
                                port = port,
                                presentation = ChatSurfacePresentation.CanvasFirst,
                                onIntent = {},
                                host = ChatSurfaceHost(openCanvas = {}),
                                modifier = Modifier.fillMaxSize(),
                                platform = ChatSurfacePlatform(showKeyboardHints = false),
                                canvas = { _ -> Box(Modifier.fillMaxSize()) },
                                dockGeometry = ChatDockGeometry.Default,
                            )
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    private fun ComposeUiTest.assertDockContentVisible(panelTag: String, barTag: String, keyboard: Dp) {
        val panel = onNodeWithTag(panelTag).getBoundsInRoot()
        val keyboardTop = onRoot().getBoundsInRoot().top + HEIGHT - keyboard
        assertTrue(panel.height > 0.dp && panel.bottom <= keyboardTop, "panel $panel must be above keyboard top $keyboardTop")
        onNodeWithTag(DOCKED_REPLY_TAG).assertVisibleInside(panel, "timeline")
        onNodeWithText(REPLY, substring = true, useUnmergedTree = true).assertVisibleInside(panel, "assistant reply")
        onNodeWithTag(barTag).assertVisibleInside(panel, "composer")
        onNodeWithTag(ComposerTestTags.DOCKED_INPUT).assertVisibleInside(panel, "composer input")
        onNodeWithTag(ComposerTestTags.SEND).assertVisibleInside(panel, "send button")
    }

    private fun SemanticsNodeInteraction.assertVisibleInside(panel: DpRect, label: String) {
        assertExists()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue(bounds.height > 0.dp && bounds.width > 0.dp, "$label must have non-zero bounds inside panel $panel; was $bounds")
        assertTrue(
            bounds.left >= panel.left && bounds.right <= panel.right && bounds.top >= panel.top && bounds.bottom <= panel.bottom,
            "$label $bounds must be fully inside panel $panel",
        )
        val visible = getBoundsInRoot()
        assertTrue(visible.height > 0.dp && visible.width > 0.dp, "$label must remain visible; was $visible")
    }

    private fun ComposeUiTest.barBottom(): Dp =
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).getBoundsInRoot().bottom - onRoot().getBoundsInRoot().top

    private companion object {
        val WIDTH = 400.dp
        val HEIGHT = 700.dp
        val KEYBOARD = 300.dp
        const val REPLY = "Here is the kitchen plan."
        const val SETTLE_MILLIS = 1_000L
    }
}
