@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarReaction
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.Hover
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.avatar.core.Petting
import com.letta.mobile.avatar.core.ReactionArg
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.haptics.Haptics
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.4: the mascot is touchable through the shared hit area, so Android, desktop and
 * web answer alike - a tap pokes and still opens the agent at once, five quick taps make it dizzy, a
 * double tap plays the next trick, a long press pets it, a hover is written by the host, a drag is
 * the director's DRAGGED and lands at its release speed, and reduced motion keeps only blinks.
 */
class MascotInteractionUiTest {
    /** A runtime that records what the director tells it. */
    private class RecordingRuntime : HeadlessAvatarRuntime() {
        val reactions = mutableListOf<Pair<AvatarReaction, Float>>()
        var hovered = false
        var petting = false
        var blinks = 0

        override fun react(reaction: AvatarReaction, arg: ReactionArg) {
            reactions += reaction to arg.value
        }

        override fun setHovered(hover: Hover) {
            hovered = hover.over
        }

        override fun setPetting(petting: Petting) {
            this.petting = petting.active
        }

        override fun onPlayGesture(gesture: AvatarGesture, fadeSeconds: Float) {
            if (gesture.id == "blink") blinks++
        }

        fun fired(reaction: AvatarReaction): List<Float> = reactions.filter { it.first == reaction }.map { it.second }
    }

    /** One recording entry per scene, kept like a real host keeps them. */
    private class RecordingHost : MascotHost {
        val runtimes = HashMap<String, RecordingRuntime>()
        val states = mutableListOf<AvatarState>()
        private val entries = HashMap<String, MascotEntry>()
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = entries.getOrPut(agentId) {
            val runtime = RecordingRuntime().also { runtimes[agentId] = it }
            object : MascotEntry(runtime, identity, applyState = { states += it }) {
                override suspend fun load() = runtime.load(AvatarModel(id = "m", displayName = "M", uri = "res://raw/mascot.riv"))
                override fun writeIdentity(identity: MascotIdentity) = Unit
                override fun dispose() = Unit
            }
        }

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            Box(modifier.testTag(FakeMascotHost.SURFACE_TAG))
        }

        fun runtime(scene: String = AGENT): RecordingRuntime = runtimes.getValue(scene)
    }

    private class Stage(val host: RecordingHost, val registry: MascotIdentityRegistry, val haptics: MutableList<LettaHapticCue>) {
        var clicks = 0
    }

    private fun ComposeUiTest.stage(
        reducedMotion: Boolean = false,
        clickable: Boolean = true,
        content: @Composable (Stage) -> Unit = { stage ->
            MascotLive(AGENT, IDENTITY, size = SIZE.dp, onClick = if (clickable) ({ stage.clicks++ }) else null, interactive = true)
        },
    ): Stage {
        val registry = MascotIdentityRegistry().also { it.update(mapOf(AGENT to IDENTITY)) }
        val stage = Stage(RecordingHost(), registry, mutableListOf())
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalMascotHost provides stage.host,
                LocalMascotRegistry provides registry,
                LocalMascotTransport provides MascotTransport(),
                LocalReducedMotion provides reducedMotion,
                LocalHaptics provides Haptics { stage.haptics += it },
            ) {
                Box(Modifier.size(WINDOW.dp)) { Box(Modifier.offset(MARGIN.dp, MARGIN.dp)) { content(stage) } }
            }
        }
        settle()
        return stage
    }

    private fun ComposeUiTest.settle(millis: Long = SETTLE_MILLIS) {
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private fun ComposeUiTest.tap() = onNodeWithTag(MASCOT_HIT_AREA_TAG).performTouchInput { click() }

    @Test
    fun aTapPokesAndStillOpensTheAgentAtOnce() = runComposeUiTest {
        val stage = stage()
        tap()
        // No frame advanced: the click and the poke land in the same event.
        assertEquals(1, stage.clicks, "the click is not delayed by the reaction")
        assertEquals(listOf(0f), stage.host.runtime().fired(AvatarReaction.POKE))
        assertEquals(listOf(LettaHapticCue.MascotTap), stage.haptics)
    }

    @Test
    fun fiveTapsInTwoSecondsMakeItDizzyOnce() = runComposeUiTest {
        val stage = stage()
        repeat(DIZZY_TAPS) {
            tap()
            settle(BETWEEN_TAPS_MILLIS)
        }
        assertEquals(DIZZY_TAPS, stage.clicks)
        assertEquals(DIZZY_TAPS, stage.host.runtime().fired(AvatarReaction.POKE).size)
        assertEquals(1, stage.host.runtime().fired(AvatarReaction.DIZZY).size)
    }

    @Test
    fun aDoubleTapPlaysTheNextTrickAndRespectsItsCooldown() = runComposeUiTest {
        val stage = stage(clickable = false)
        val hit = onNodeWithTag(MASCOT_HIT_AREA_TAG)
        hit.performTouchInput { doubleClick() }
        settle(TRICK_GAP_MILLIS)
        hit.performTouchInput { doubleClick() }
        settle(BETWEEN_TAPS_MILLIS)
        hit.performTouchInput { doubleClick() } // inside the 1.5 s cooldown: two pokes, no trick
        settle(BETWEEN_TAPS_MILLIS)
        assertEquals(listOf(0f, 1f), stage.host.runtime().fired(AvatarReaction.TRICK))
        assertEquals(0, stage.clicks)
    }

    @Test
    fun aDoubleClickOnAClickableMascotClicksOnce() = runComposeUiTest {
        val stage = stage()
        onNodeWithTag(MASCOT_HIT_AREA_TAG).performTouchInput { doubleClick() }
        assertEquals(1, stage.clicks, "the second tap is the trick, not a second open")
        assertEquals(listOf(0f), stage.host.runtime().fired(AvatarReaction.TRICK))
    }

    @Test
    fun aLongPressPetsWhileHeldAndReleasesAfter() = runComposeUiTest {
        val stage = stage(clickable = false)
        val hit = onNodeWithTag(MASCOT_HIT_AREA_TAG)
        hit.performTouchInput { down(center) }
        settle(HOLD_MILLIS)
        assertTrue(stage.host.runtime().petting, "held still: petting")
        hit.performTouchInput { up() }
        settle(BETWEEN_TAPS_MILLIS)
        assertTrue(stage.host.runtime().petting, "petting lingers a moment after the lift")
        settle(PET_RELEASE_MILLIS)
        assertFalse(stage.host.runtime().petting)
        assertTrue(stage.host.runtime().fired(AvatarReaction.POKE).isEmpty(), "a long press is no poke")
    }

    @Test
    fun theHostWritesHover() = runComposeUiTest {
        val stage = stage()
        val hit = onNodeWithTag(MASCOT_HIT_AREA_TAG)
        hit.performMouseInput { moveTo(center) }
        settle(FRAME_MILLIS)
        assertTrue(stage.host.runtime().hovered)
        hit.performMouseInput { moveTo(Offset(-OUTSIDE_PX, -OUTSIDE_PX)) }
        settle(FRAME_MILLIS)
        assertFalse(stage.host.runtime().hovered)
    }

    @Test
    fun aPokeWhileDreamingWakesItFirst() = runComposeUiTest {
        val stage = stage()
        stage.registry.setPresence(AGENT, AgentPresence(dreaming = true))
        settle()
        tap()
        val order = stage.host.runtime().reactions.map { it.first }.filter { it == AvatarReaction.WAKE || it == AvatarReaction.POKE }
        assertEquals(listOf(AvatarReaction.WAKE, AvatarReaction.POKE), order.take(2))
    }

    @Test
    fun underReducedMotionAReactionIsABlinkAndTheClickStillGoes() = runComposeUiTest {
        val stage = stage(reducedMotion = true)
        val blinksBefore = stage.host.runtime().blinks
        tap()
        assertEquals(1, stage.clicks)
        assertTrue(stage.host.runtime().fired(AvatarReaction.POKE).isEmpty(), "no poke animation")
        assertTrue(stage.host.runtime().blinks > blinksBefore, "a blink instead")
        assertEquals(listOf(LettaHapticCue.MascotTap), stage.haptics, "only the confirm tick")
    }

    @Test
    fun aDragIsTheDirectorsAndLandsAtItsReleaseSpeed() = runComposeUiTest {
        val transport = MascotTransport()
        val stage = stage { _ ->
            CompositionLocalProvider(LocalMascotTransport provides transport) {
                MascotTransportLayer(reducedMotion = false) {
                    MascotSeat(AGENT, MascotStage.COMPOSER_COMPANION, size = SIZE.dp, onDrag = { _, _ -> }, empty = {})
                }
            }
        }
        onNodeWithTag(FakeMascotHost.SURFACE_TAG).performTouchInput {
            swipe(center, center + Offset(-DRAG_PX, -DRAG_PX), durationMillis = DRAG_MILLIS)
        }
        settle()
        assertTrue(AvatarState.DRAGGED in stage.host.states, "the director held DRAGGED: ${stage.host.states}")
        assertEquals(AvatarState.IDLE, stage.host.states.last(), "put down")
        val landings = stage.host.runtime(LAYER_SCENE).fired(AvatarReaction.LAND)
        assertTrue(landings.any { it > 0f }, "the drop lands with its release speed: $landings")
        assertTrue(LettaHapticCue.GestureStart in stage.haptics && LettaHapticCue.GestureEnd in stage.haptics, "${stage.haptics}")
    }

    private companion object {
        const val AGENT = "agent-touch"
        const val LAYER_SCENE = "mascot-focus-0"
        val IDENTITY = MascotIdentity(MascotShape.entries.first(), 0xFF00AA88.toInt())
        const val SIZE = 160
        const val WINDOW = 600
        const val MARGIN = 100
        const val SETTLE_MILLIS = 1_000L
        const val FRAME_MILLIS = 16L
        const val BETWEEN_TAPS_MILLIS = 350L
        const val TRICK_GAP_MILLIS = 2_000L
        const val HOLD_MILLIS = 500L
        const val PET_RELEASE_MILLIS = 400L
        const val DIZZY_TAPS = 5
        const val OUTSIDE_PX = 40f
        const val DRAG_PX = 120f
        const val DRAG_MILLIS = 150L
    }
}
