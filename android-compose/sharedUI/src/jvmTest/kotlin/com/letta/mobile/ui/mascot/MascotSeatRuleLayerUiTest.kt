@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.8: Android mounts the transport layer with [MascotLayerDrawing.SEAT_RULE].
 * Opening the agent pane hands the one character from the composer companion to the pane's hero
 * (never two at once), the hero keeps the avatar's live-or-still rule (an idle agent is its still,
 * as ShellDrawerMascotTest pins in place), the layer draws in the agent's own scene so the host's
 * one-live-driver rule spans every avatar of it, and a seat that answers nothing leaves the touch
 * to what is under it even over a renderer with its own pointer filter.
 */
class MascotSeatRuleLayerUiTest {
    private class Stage(val shell: FakeMascotShell, val paneOpen: MutableState<Boolean>)

    private fun ComposeUiTest.stage(
        drawing: MascotLayerDrawing = MascotLayerDrawing.SEAT_RULE,
        activity: AgentActivityKind = AgentActivityKind.IDLE,
        host: MascotHost = FakeMascotHost,
        extra: @Composable () -> Unit = {},
    ): Stage {
        val shell = FakeMascotShell(AGENT, layerMounted = false, host = host, drawing = drawing)
        shell.registry.setPresence(AGENT, AgentPresence(activity = activity))
        val paneOpen = mutableStateOf(false)
        // A live mascot ticks every frame, so the clock never idles on its own.
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                DriveMascotStage(AgentId(AGENT), agentPaneVisible = paneOpen.value)
                MascotTransportLayer(reducedMotion = true) {
                    Box(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
                        val companion = Modifier.offset(COMPANION_X.dp, 500.dp)
                        MascotSeat(AGENT, MascotStage.COMPOSER_COMPANION, size = SEAT.dp, modifier = companion, empty = {})
                        MascotSeat(
                            AGENT,
                            MascotStage.AGENT_PANE_HERO,
                            size = SEAT.dp,
                            modifier = Modifier.offset(HERO_X.dp, 40.dp),
                            live = mascotAtWork(AGENT),
                            empty = {},
                        )
                        extra()
                    }
                }
            }
        }
        settle()
        return Stage(shell, paneOpen)
    }

    private fun ComposeUiTest.settle() = repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }

    private fun ComposeUiTest.characters(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(MascotSemantics.Identity), useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.theCharacter(): SemanticsNode = characters().single()

    private fun SemanticsNode.live(): Boolean = config[MascotSemantics.Live]

    /** Whether [bounds] stands at the seat placed [x] dp from the left. */
    private fun ComposeUiTest.isAt(bounds: Rect, x: Int): Boolean = abs(bounds.left - x * density.density) < SEAT * density.density

    @Test
    fun openingThePaneHandsTheOneLiveCharacterFromTheCompanionToTheHero() = runComposeUiTest {
        val stage = stage(activity = AgentActivityKind.THINKING)
        val atRest = theCharacter()
        assertTrue(atRest.live(), "the working companion is live")
        assertTrue(isAt(atRest.boundsInRoot, COMPANION_X), "standing at the companion: ${atRest.boundsInRoot}")

        stage.paneOpen.value = true
        settle()
        val atHero = theCharacter()
        assertTrue(atHero.live(), "the working agent's hero is live")
        assertTrue(isAt(atHero.boundsInRoot, HERO_X), "carried to the hero: ${atHero.boundsInRoot}")

        stage.paneOpen.value = false
        settle()
        assertTrue(isAt(theCharacter().boundsInRoot, COMPANION_X), "and back to rest")
    }

    @Test
    fun anIdleAgentsHeroIsItsStillOnThePhone() = runComposeUiTest {
        val stage = stage()
        stage.paneOpen.value = true
        settle()
        assertEquals(false, theCharacter().live(), "an idle hero is the still every avatar of the agent shows")
        stage.shell.registry.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.THINKING))
        settle()
        assertEquals(true, theCharacter().live(), "and moves once the agent works")
    }

    @Test
    fun theDesktopLayerStillDrawsAnIdleHeroLive() = runComposeUiTest {
        val stage = stage(drawing = MascotLayerDrawing.LIVE_SLOTS)
        stage.paneOpen.value = true
        settle()
        assertEquals(true, theCharacter().live())
    }

    @Test
    fun theLayerDrawsInTheAgentsOwnSceneSoOneDriverSpansEveryAvatar() = runComposeUiTest {
        val host = CachingMascotHost()
        stage(activity = AgentActivityKind.THINKING, host = host, extra = {
            MascotAvatar(AGENT, size = SEAT.dp, modifier = Modifier.offset(300.dp, 300.dp), fallback = {})
        })
        assertEquals(setOf(AGENT), host.made.keys, "one scene for the agent, shared by the layer and its avatar")
    }

    @Test
    fun aSeatThatAnswersNothingLeavesTheTouchToWhatIsUnderIt() = runComposeUiTest {
        var taps = 0
        stage(activity = AgentActivityKind.THINKING, host = PointerObservingMascotHost, extra = {
            Box(Modifier.offset(COMPANION_X.dp, 500.dp).size(SEAT.dp).testTag(UNDER).clickable { taps++ })
        })
        val companion = theCharacter().boundsInRoot
        onRoot().performTouchInput { click(companion.center) }
        settle()
        assertEquals(1, taps, "the chat head under the character took the tap")
    }

    private companion object {
        const val AGENT = "agent-phone"
        const val UNDER = "under-the-companion"
        const val WIDTH = 400
        const val HEIGHT = 600
        const val SEAT = 48
        const val COMPANION_X = 20
        const val HERO_X = 300
        const val SETTLE_FRAMES = 20
    }
}
