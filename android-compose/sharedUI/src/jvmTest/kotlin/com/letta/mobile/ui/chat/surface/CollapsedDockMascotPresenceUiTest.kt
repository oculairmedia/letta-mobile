@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutCodec
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSemantics
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.mascot.MascotTransportLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-8kpk7: the minimised desktop chat always shows its agent above the bar. Composed as
 * the desktop composes it (the window's mascot transport layer, the host's bubble-layout prefs read
 * from storage, a canvas behind the dock), the character is drawn over the collapsed mascot's spot
 * at a real size, whatever the stored prefs hold, and an agent without a mascot keeps a stand-in.
 */
class CollapsedDockMascotPresenceUiTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-10-12T18:02:00Z")
    private val reply = UiMessage(
        id = "a1",
        role = "assistant",
        content = "Here's an L-shaped plan.",
        timestamp = "2026-10-12T18:02:09Z",
        runId = "run-1",
    )

    private class Port(agentId: String) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = emptyList<UiMessage>().toPersistentList(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = agentId,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private fun storage(stored: CanvasBubbleLayout?) = LayoutPrefStorage(load = { stored }, save = {}, io = Dispatchers.Unconfined)

    private fun ComposeUiTest.showDesktop(
        shell: FakeMascotShell,
        stored: LayoutPrefStorage<CanvasBubbleLayout>,
        agentId: String = AGENT,
        /** A visible agent pane beside the page, with its hero seat (the desktop sidebar). */
        agentPane: Boolean = false,
    ) {
        var geometry by mutableStateOf(ChatDockGeometry(collapsed = true))
        val port = Port(agentId).also {
            it.uiState.value = it.uiState.value.copy(messages = listOf(prompt, reply).toPersistentList())
        }
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MaterialTheme {
                    Box(Modifier.size(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp)) {
                        MascotTransportLayer {
                            if (agentPane) MascotSeat(agentId, MascotStage.AGENT_PANE_HERO, HERO_SIZE.dp, empty = {})
                            CanvasBubbleLayoutHost(CanvasBubbleLayout.Desktop, stored) {
                                ChatSurface(
                                    port = port,
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
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
    }

    /** The collapsed mascot's spot over the bar, which must have a real size. */
    private fun ComposeUiTest.mascotSpot(): Rect {
        val spot = onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).getBoundsInRoot()
        val rect = Rect(spot.left.value, spot.top.value, spot.right.value, spot.bottom.value)
        assertTrue(rect.width >= CanvasBubbleLayout.MASCOT_MIN_DP - 1f, "the mascot spot is ${rect.width}dp wide")
        return rect
    }

    /** The character the layer draws, over [spot]: present, a real size, centred on it. */
    private fun ComposeUiTest.assertCharacterOver(spot: Rect, shell: FakeMascotShell) {
        assertNotNull(
            shell.transport.seat(MascotTransport.SeatKey(AGENT, MascotStage.COMPOSER_COMPANION)),
            "the collapsed dock seats the companion",
        )
        assertEquals(MascotStage.COMPOSER_COMPANION, shell.transport.activeStage(AGENT), "the character stands at the dock")
        val surfaces = onAllNodesWithTag(FakeMascotHost.SURFACE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(surfaces.isNotEmpty(), "the layer draws the character")
        val density = density.density
        val drawn = surfaces.map { node ->
            val b = node.boundsInRoot
            Rect(b.left / density, b.top / density, b.right / density, b.bottom / density)
        }
        assertTrue(
            drawn.any { it.width > 0f && it.height > 0f && spot.contains(it.center) },
            "the character is drawn over the mascot spot $spot; drawn at $drawn",
        )
    }

    @Test
    fun withNoStoredPrefsTheDefaultSizedCharacterStandsOverTheBar() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        showDesktop(shell, storage(null))
        val spot = mascotSpot()
        assertTrue(kotlin.math.abs(spot.width - CanvasBubbleLayout.Desktop.mascotSizeDp) < 1.5f, "default size, was ${spot.width}")
        assertCharacterOver(spot, shell)
    }

    @Test
    fun atTheSmallestSizeTheCharacterStillStandsOverTheBar() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        showDesktop(shell, storage(CanvasBubbleLayout.Desktop.copy(mascotSizeDp = CanvasBubbleLayout.MASCOT_MIN_DP)))
        assertCharacterOver(mascotSpot(), shell)
    }

    @Test
    fun aZeroOrDamagedStoredSizeIsClampedAndTheCharacterStillShows() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        showDesktop(shell, storage(CanvasBubbleLayout.Desktop.copy(mascotSizeDp = 0f)))
        val spot = mascotSpot()
        assertTrue(spot.width >= CanvasBubbleLayout.MASCOT_MIN_DP - 1f, "clamped, was ${spot.width}")
        assertCharacterOver(spot, shell)
    }

    @Test
    fun aLegacyPrefsFileWithoutTheNewKeysFallsBackToTheDefaults() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        // A file written before the bubble layout existed: only unrelated keys.
        val legacy = CanvasBubbleLayoutCodec.decode(mapOf("chat.dock.anchorX" to "0.5"), CanvasBubbleLayout.Desktop)
        showDesktop(shell, storage(legacy))
        val spot = mascotSpot()
        assertTrue(kotlin.math.abs(spot.width - CanvasBubbleLayout.Desktop.mascotSizeDp) < 1.5f, "default size, was ${spot.width}")
        assertCharacterOver(spot, shell)
    }

    @Test
    fun aStorageThatThrowsStillShowsTheCharacter() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        showDesktop(shell, LayoutPrefStorage(load = { error("corrupt file") }, save = {}, io = Dispatchers.Unconfined))
        assertCharacterOver(mascotSpot(), shell)
    }

    @Test
    fun whileTheCharacterStandsInTheDockNoStandInIsDrawn() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        showDesktop(shell, storage(null))
        assertCharacterOver(mascotSpot(), shell)
        onNodeWithTag(DOCK_COLLAPSED_STAND_IN_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * The desktop's visible sidebar holds the agent pane's hero, and the transport sends the live
     * character there (DriveMascotStage): the minimised dock shows the same character, live
     * (letta-mobile-ckmub.15), in the look the hero has - the editor's unsaved preview included.
     */
    @Test
    fun whileTheAgentPaneHoldsTheCharacterTheDockShowsItLive() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        val previewed = MascotIdentity(MascotShape.entries.last(), PREVIEW_COLOR)
        shell.transport.preview(AGENT, previewed)
        shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        showDesktop(shell, storage(null), agentPane = true)
        assertEquals(MascotStage.AGENT_PANE_HERO, shell.transport.activeStage(AGENT), "the pane's hero holds the character")
        val spot = mascotSpot()
        val standIn = onNodeWithTag(DOCK_COLLAPSED_STAND_IN_TAG, useUnmergedTree = true)
        val bounds = standIn.getBoundsInRoot()
        assertTrue((bounds.right - bounds.left).value > 0f, "the stand-in has a size")
        val centre = androidx.compose.ui.geometry.Offset(((bounds.left + bounds.right) / 2).value, ((bounds.top + bounds.bottom) / 2).value)
        assertTrue(spot.contains(centre), "the stand-in is over the mascot spot")
        val px = density.density
        val drawn = onAllNodes(SemanticsMatcher.keyIsDefined(MascotSemantics.Live), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { spot.contains(it.boundsInRoot.center / px) }
        assertTrue(drawn.isNotEmpty(), "a mascot is drawn over the dock")
        drawn.forEach { node ->
            assertEquals(true, node.config[MascotSemantics.Live], "the dock's character is live, not a still")
            assertEquals(previewed, node.config[MascotSemantics.Identity], "the dock shows the hero's look")
        }
    }

    @Test
    fun whenTheHostCannotDrawTheSceneTheDockShowsTheAgentsInitial() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false, host = SceneFailingHost)
        showDesktop(shell, storage(null))
        mascotSpot()
        onNodeWithTag(DOCK_COLLAPSED_STAND_IN_TAG, useUnmergedTree = true).assertExists()
        onNodeWithText("M", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anAgentWithoutAMascotKeepsItsSphereOverTheBar() = runComposeUiTest {
        val shell = FakeMascotShell("someone-else", layerMounted = false)
        showDesktop(shell, storage(null))
        assertTrue(mascotSpot().width > 0f)
    }

    /** A renderer that says it can draw but never brings up a scene (a failed Rive load). */
    private object SceneFailingHost : MascotHost {
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? = null

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val WINDOW_WIDTH = 1280
        const val WINDOW_HEIGHT = 800
        const val SETTLE_MILLIS = 2_000L
        const val HERO_SIZE = 80
        const val PREVIEW_COLOR = 0xFF3355CC.toInt()
    }
}
