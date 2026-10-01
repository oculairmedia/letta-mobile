@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/** letta-mobile-bglj6.1: the docked panel grows into the full page and back, without a jump. */
class ChatSurfaceMorphUiTest {
    private val ready = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        agentName = "Meridian",
        messages = persistentListOf(
            UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z"),
        ),
    )

    private class Harness(initial: ChatSurfacePresentation) {
        var presentation by mutableStateOf(initial)
        var geometry by mutableStateOf(ChatDockGeometry(anchorX = 0.2f, anchorY = 0.6f, widthDp = 420f, heightDp = 300f))
        val intents = mutableListOf<ChatSurfaceIntent>()

        fun raise(intent: ChatSurfaceIntent) {
            intents += intent
            presentation = ChatSurfaceModeReducer.reduce(presentation, intent)
        }
    }

    private fun ComposeUiTest.show(
        initial: ChatSurfacePresentation = ChatSurfacePresentation.CanvasFirst,
        reducedMotion: Boolean = false,
        withCanvas: Boolean = true,
    ): Harness {
        val harness = Harness(initial)
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                MaterialTheme {
                    Box(Modifier.size(width = 1000.dp, height = 800.dp).testTag(ROOT_TAG)) {
                        ChatSurface(
                            port = ChatSurfaceUiTest.TestPort(ready, ChatComposerUiState(canSend = true)),
                            presentation = harness.presentation,
                            onIntent = harness::raise,
                            host = ChatSurfaceHost(),
                            canvas = if (withCanvas) ({ _ -> Box(Modifier.fillMaxSize()) }) else null,
                            dockGeometry = harness.geometry,
                            onDockGeometryChange = { harness.geometry = it },
                        )
                    }
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        return harness
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(ChatMotionTokens.SurfaceMorph.MILLIS.toLong() + SETTLE_SLACK_MILLIS)
    }

    private fun ComposeUiTest.halfway() {
        mainClock.advanceTimeBy(ChatMotionTokens.SurfaceMorph.MILLIS.toLong() / 2)
    }

    private fun ComposeUiTest.morphBounds(): DpRect = onNodeWithTag(SURFACE_MORPH_TAG).getBoundsInRoot()

    private fun assertStrictlyBetween(mid: DpRect, from: DpRect, to: DpRect) {
        assertTrue(mid.width > from.width && mid.width < to.width, "width: $from -> $mid -> $to")
        assertTrue(mid.height > from.height && mid.height < to.height, "height: $from -> $mid -> $to")
        assertTrue(mid.left < from.left && mid.left > to.left, "left: $from -> $mid -> $to")
        assertTrue(mid.top < from.top && mid.top > to.top, "top: $from -> $mid -> $to")
    }

    @Test
    fun expandingMorphsThePanelIntoTheFullPage() = runComposeUiTest {
        show()
        val panel = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val full = onNodeWithTag(ROOT_TAG).getBoundsInRoot()
        onNodeWithTag(ComposerTestTags.EXPAND).performClick()
        halfway()
        assertStrictlyBetween(morphBounds(), panel, full)
        settle()
        onNodeWithTag(SURFACE_MORPH_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_PANEL_TAG).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.CARD).assertExists()
    }

    @Test
    fun collapsingLandsBackAtTheSavedGeometry() = runComposeUiTest {
        val harness = show()
        val panel = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val full = onNodeWithTag(ROOT_TAG).getBoundsInRoot()
        harness.raise(ChatSurfaceIntent.Expand)
        settle()
        onNodeWithTag(ComposerTestTags.SHOW_CANVAS).performClick()
        halfway()
        assertStrictlyBetween(morphBounds(), panel, full)
        settle()
        onNodeWithTag(SURFACE_MORPH_TAG).assertDoesNotExist()
        assertEquals(panel, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot())
        assertEquals(listOf(ChatSurfaceIntent.Expand, ChatSurfaceIntent.OpenCanvas), harness.intents)
    }

    @Test
    fun togglingMidWayReversesFromWhereItIs() = runComposeUiTest {
        val harness = show()
        val panel = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        val full = onNodeWithTag(ROOT_TAG).getBoundsInRoot()
        harness.raise(ChatSurfaceIntent.Expand)
        halfway()
        val turn = morphBounds()
        harness.raise(ChatSurfaceIntent.Collapse)
        mainClock.advanceTimeBy(FEW_FRAMES_MILLIS)
        val turning = morphBounds()
        mainClock.advanceTimeBy(FEW_FRAMES_MILLIS)
        val back = morphBounds()
        // No jump at the turn: it carries on from where it was, then heads back.
        val travel = full.width - panel.width
        assertTrue(abs((turning.width - turn.width).value) < travel.value / 4, "continuous: $turn -> $turning")
        assertTrue(back.width < turning.width && back.width > panel.width, "reverses: $turning -> $back")
        settle()
        assertEquals(panel, onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot())
    }

    @Test
    fun reducedMotionSwapsInstantly() = runComposeUiTest {
        val harness = show(reducedMotion = true)
        harness.raise(ChatSurfaceIntent.Expand)
        mainClock.advanceTimeByFrame()
        onNodeWithTag(SURFACE_MORPH_TAG).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.CARD).assertExists()
        harness.raise(ChatSurfaceIntent.Collapse)
        mainClock.advanceTimeByFrame()
        onNodeWithTag(SURFACE_MORPH_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_PANEL_TAG).assertExists()
    }

    @Test
    fun showCanvasRaisesOpenCanvasFromTheFullPage() = runComposeUiTest {
        val harness = show(initial = ChatSurfacePresentation.ChatFirst)
        onNodeWithTag(ComposerTestTags.SHOW_CANVAS).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), harness.intents)
    }

    @Test
    fun showCanvasIsAbsentWithoutACanvas() = runComposeUiTest {
        show(initial = ChatSurfacePresentation.ChatFirst, withCanvas = false)
        onNodeWithTag(ComposerTestTags.CARD).assertExists()
        onNodeWithTag(ComposerTestTags.SHOW_CANVAS).assertDoesNotExist()
    }

    private companion object {
        const val ROOT_TAG = "morph-root"
        const val SETTLE_SLACK_MILLIS = 100L
        const val FEW_FRAMES_MILLIS = 48L
    }
}
