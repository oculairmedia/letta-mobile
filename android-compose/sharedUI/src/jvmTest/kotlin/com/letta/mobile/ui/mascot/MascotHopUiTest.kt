@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.3: a hop between seats is a spring - the character travels (never snaps),
 * overshoots once, settles on the destination and lands once - with the renderer's node at the
 * destination size the whole way and no recomposition per frame; under reduced motion it snaps.
 */
class MascotHopUiTest {
    private class Stage(val shell: FakeMascotShell, val landings: MutableList<Float>)

    private fun ComposeUiTest.stage(reducedMotion: Boolean, host: MascotHost = FakeMascotHost): Stage {
        val shell = FakeMascotShell(AGENT, layerMounted = false, host = host)
        val landings = mutableListOf<Float>()
        shell.transport.motionListener = MascotMotionListener { agent, impact ->
            assertEquals(AGENT, agent.value)
            landings += impact
        }
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MascotTransportLayer(reducedMotion = reducedMotion) {
                    Box(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
                        val companion = Modifier.offset(40.dp, 520.dp)
                        val hero = Modifier.offset(640.dp, 40.dp)
                        MascotSeat(AGENT, MascotStage.COMPOSER_COMPANION, size = SMALL.dp, modifier = companion, empty = {})
                        MascotSeat(AGENT, MascotStage.AGENT_PANE_HERO, size = LARGE.dp, modifier = hero, empty = {})
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
        return Stage(shell, landings)
    }

    private fun ComposeUiTest.character(): Rect = onNodeWithTag(FakeMascotHost.SURFACE_TAG).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.nodeWidth(): Int = onNodeWithTag(FakeMascotHost.SURFACE_TAG).fetchSemanticsNode().size.width

    @Test
    fun aHopSpringsAlongAPathAndLandsOnce() = runComposeUiTest {
        val stage = stage(reducedMotion = false)
        assertEquals(1, stage.landings.size, "the entrance lands")
        stage.landings.clear()
        val from = character().center
        stage.shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        mainClock.advanceTimeByFrame()
        val destinationWidth = nodeWidth()
        val centers = mutableListOf(from)
        val compositionsAtTakeOff = stage.shell.transport.transportedCompositions
        // te3n1.2: the eyes lead for a beat first, then the body flies.
        repeat(LEAD_FRAMES + FLIGHT_FRAMES) {
            mainClock.advanceTimeByFrame()
            centers += character().center
            assertEquals(destinationWidth, nodeWidth(), "the renderer keeps the destination size")
        }
        val to = centers.last()
        val distance = (to - from).getDistance()
        assertTrue(distance > 0f)
        assertEquals(compositionsAtTakeOff, stage.shell.transport.transportedCompositions, "no recomposition per frame")
        // Never a snap: no frame covers more than a quarter of the way.
        centers.zipWithNext().forEach { (a, b) -> assertTrue((b - a).getDistance() < distance / 4f, "jumped $a -> $b") }
        // Somewhere in between half-way, and past the seat once before it settles back on it.
        assertTrue(centers.any { abs((it - from).getDistance() - distance / 2f) < distance / 4f }, "travelled the path")
        assertTrue(centers.any { it.x > to.x + 1f }, "overshot the seat")
        assertTrue(abs(to.x - centers[centers.size - 2].x) < 1f, "settled")
        assertEquals(1, stage.landings.size, "landed once")
    }

    @Test
    fun whenTheRigCarriesTheBodyTheLayerOnlyMovesIt() = runComposeUiTest {
        val stage = stage(reducedMotion = false)
        stage.shell.transport.rigCarriesBody = true
        stage.shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        var moved = false
        val start = character().center
        repeat(LEAD_FRAMES + FLIGHT_FRAMES / 2) {
            mainClock.advanceTimeByFrame()
            val shown = character()
            if (shown.center != start) moved = true
            assertEquals(shown.width, shown.height, 0.5f, "no squash, stretch or lean on the layer")
        }
        assertTrue(moved, "still carried along the hop")
        assertNotNull(stage.shell.transport.bodyOf(AgentId(AGENT)), "the director can read the body's motion")
        assertEquals(2, stage.landings.size, "the entrance's and the hop's landings still reach the director")
    }

    @Test
    fun theEyesLeadToTheNextSeatBeforeTheBodyHops() = runComposeUiTest {
        val host = CachingMascotHost()
        val stage = stage(reducedMotion = false, host = host)
        val from = character().center
        stage.shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        repeat(LEAD_FRAMES - 4) { mainClock.advanceTimeByFrame() }
        assertEquals(from, character().center, "the body waits on the eyes")
        val gaze = assertNotNull(host.made["mascot-focus-0"], "the slot's scene").lastGaze
        // The hero seat is up and to the right of the companion.
        assertTrue(gaze.lookX > 0.1f && gaze.lookY < 0f, "the eyes are already on the next seat: $gaze")
        repeat(FLIGHT_FRAMES / 2) { mainClock.advanceTimeByFrame() }
        assertTrue((character().center - from).getDistance() > 1f, "then it hops")
    }

    @Test
    fun underReducedMotionAHopSnapsWithNoSquash() = runComposeUiTest {
        val stage = stage(reducedMotion = true)
        stage.shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val shown = character()
        val settledCenter = shown.center
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
        assertEquals(settledCenter, character().center, "already there")
        assertEquals(nodeWidth().toFloat(), shown.width, 0.5f, "no squash or stretch")
        assertEquals(shown.width, shown.height, 0.5f, "no lean")
        assertTrue(stage.landings.isEmpty(), "no landing")
    }

    @Test
    fun underReducedMotionTheEntranceIsAShortFade() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false)
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MascotTransportLayer(reducedMotion = true) {
                    MascotSeat(AGENT, MascotStage.COMPOSER_COMPANION, size = SMALL.dp, empty = {})
                }
            }
        }
        // The seat publishes its bounds a frame or two after layout; the layer draws from then.
        repeat(SEAT_FRAMES) { mainClock.advanceTimeByFrame() }
        val width = nodeWidth().toFloat()
        // Full size from its first frame: only its opacity comes in.
        assertEquals(width, character().width, 0.5f)
        assertTrue(shell.transport.flight("mascot-focus-0").presence.value < 1f, "still fading in")
        mainClock.advanceTimeBy(REDUCED_FADE_MILLIS.toLong() + FRAME_MILLIS)
        assertEquals(1f, shell.transport.flight("mascot-focus-0").presence.value)
    }

    private companion object {
        const val AGENT = "agent-hop"
        const val WIDTH = 800
        const val HEIGHT = 600
        const val SMALL = 48
        const val LARGE = 96
        const val SETTLE_MILLIS = 2_000L
        const val FLIGHT_FRAMES = 60

        /** MascotDirector.Config.seatLeadSeconds (0.45 s) in 16 ms frames, rounded up. */
        const val LEAD_FRAMES = 29
        const val FRAME_MILLIS = 16L
        const val SEAT_FRAMES = 3
    }
}
