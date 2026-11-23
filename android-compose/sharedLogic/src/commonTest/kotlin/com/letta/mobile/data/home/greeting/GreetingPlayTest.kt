package com.letta.mobile.data.home.greeting

import com.letta.mobile.data.home.greeting.GreetingInput.Arrived
import com.letta.mobile.data.home.greeting.GreetingInput.Cast
import com.letta.mobile.data.home.greeting.GreetingInput.Connection
import com.letta.mobile.data.home.greeting.GreetingInput.PointerOver
import com.letta.mobile.data.home.greeting.GreetingInput.ReducedMotion
import com.letta.mobile.data.home.greeting.GreetingInput.RunFinished
import com.letta.mobile.data.home.greeting.GreetingInput.Tapped
import com.letta.mobile.data.home.greeting.GreetingInput.Tick
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GreetingPlayTest {
    private fun cast(vararg moods: GreetingMood) = Cast(moods.mapIndexed { i, mood -> GreetingCastMember("a$i", mood) })

    private class Run(seed: Int = 7) {
        private val random = Random(seed)
        var state = GreetingState()

        fun send(vararg inputs: GreetingInput): Run {
            inputs.forEach { state = GreetingPlay.reduce(state, it, random) }
            return this
        }

        /** Ticks [seconds] in 1/60 s frames. */
        fun advance(seconds: Float): Run {
            repeat((seconds * FPS).roundToInt()) { send(Tick(1f / FPS)) }
            return this
        }

        fun actor(id: String) = state.actors.first { it.id == id }
    }

    @Test
    fun arrivalGreetsEveryIdleMascotOnce() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE, GreetingMood.IDLE), Arrived()).advance(FRAME)
        run.state.actors.forEach {
            assertEquals(GreetingBehavior.GREETING, it.behavior)
            assertEquals(GreetingGesture.WAVE, it.gesture)
            assertEquals(1, it.gestureSerial)
        }
        // The waves pass on to the neighbours, but the cooldown keeps each mascot to one wave.
        run.advance(3f)
        run.state.actors.forEach { assertEquals(1, it.gestureSerial) }
        assertEquals(1, run.state.markBootSerial)
    }

    @Test
    fun bootFiresOnlyOnTheFirstArrival() {
        val run = Run().send(cast(GreetingMood.IDLE), Arrived(), Arrived(returning = true))
        assertEquals(1, run.state.markBootSerial)
    }

    @Test
    fun aBusyMascotOnlyLooks() {
        val run = Run().send(cast(GreetingMood.WORKING, GreetingMood.IDLE), Arrived()).advance(FRAME)
        val busy = run.actor("a0")
        assertEquals(GreetingBehavior.NOTICING, busy.behavior)
        assertNull(busy.gesture)
        assertEquals(GreetingTarget.User, busy.target)
    }

    @Test
    fun aNeighbourInTroubleDrawsConcern() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE, GreetingMood.IDLE))
        run.send(cast(GreetingMood.IDLE, GreetingMood.ERROR, GreetingMood.IDLE)).advance(FRAME)
        listOf("a0", "a2").forEach { id ->
            val actor = run.actor(id)
            assertEquals(GreetingBehavior.CONCERNED, actor.behavior)
            assertEquals(GreetingTarget.Actor("a1"), actor.target)
            assertEquals(GreetingTarget.Actor("a1"), run.state.intentFor(id).lean)
        }
    }

    @Test
    fun aFinishedRunDrawsACheerAndAHopTowardIt() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE), RunFinished("a1", ok = true)).advance(FRAME)
        val cheer = run.state.intentFor("a0")
        assertEquals(GreetingGesture.CHEER, cheer.gesture)
        assertEquals(GreetingTarget.Actor("a1"), cheer.hop)
    }

    @Test
    fun aFailedRunIsNoCheer() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE), RunFinished("a1", ok = false)).advance(FRAME)
        assertEquals(GreetingBehavior.SETTLED, run.actor("a0").behavior)
    }

    @Test
    fun hoverPerksTheMascotAndTurnsTheOthers() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE, GreetingMood.IDLE), PointerOver(GreetingTarget.Actor("a1")))
            .advance(FRAME)
        assertTrue(run.state.intentFor("a1").perk)
        assertEquals(GreetingTarget.Actor("a1"), run.state.intentFor("a0").look)
        assertEquals(GreetingTarget.Actor("a1"), run.state.intentFor("a2").look)
    }

    @Test
    fun aTappedMascotWavesAtTheUser() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE), Tapped(GreetingTarget.Actor("a0"))).advance(FRAME)
        assertEquals(GreetingGesture.WAVE, run.actor("a0").gesture)
        assertEquals(GreetingTarget.User, run.actor("a0").target)
        assertEquals(GreetingTarget.Actor("a0"), run.actor("a1").target)
    }

    @Test
    fun everyReactionRunsOutAndTheCastSettles() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE, GreetingMood.IDLE), Arrived()).advance(SETTLE_SECONDS)
        run.state.actors.forEach { assertEquals(GreetingBehavior.SETTLED, it.behavior) }
        assertTrue(run.state.pending.isEmpty())
    }

    @Test
    fun habituationStopsTheSameStimulusFromHoldingForever() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE))
        // The pointer keeps moving on and off one mascot: the neighbour's interest in it runs down.
        repeat(HOVER_ROUNDS) {
            run.send(PointerOver(GreetingTarget.Actor("a1"))).advance(1f).send(PointerOver(null)).advance(FRAME)
        }
        val interest = run.actor("a0").interest[GreetingTarget.Actor("a1")] ?: 1f
        assertTrue(interest < LOW_INTEREST, "interest $interest")
    }

    @Test
    fun anUnsettledConnectionHuddlesTheCastAndLockingFiresSuccess() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE), Connection(GreetingConnection.CONNECTING)).advance(SETTLE_SECONDS)
        run.state.actors.forEach { assertEquals(GreetingBehavior.HUDDLED, it.behavior) }
        assertEquals(GreetingMarkState.CONNECTING, run.state.markIntent.state)
        assertEquals(GreetingTarget.Mark, run.state.intentFor("a0").lean)

        run.send(Connection(GreetingConnection.CONNECTED)).advance(SETTLE_SECONDS)
        assertEquals(1, run.state.markIntent.successSerial)
        assertEquals(GreetingMarkState.IDLE, run.state.markIntent.state)
        run.state.actors.forEach { assertEquals(GreetingBehavior.SETTLED, it.behavior) }
    }

    @Test
    fun theMarkShowsLoadingOnlyWithNoMascots() {
        val empty = Run().send(GreetingInput.Hydrating(true))
        assertEquals(GreetingMarkState.LOADING, empty.state.markIntent.state)
        val withCast = Run().send(cast(GreetingMood.IDLE), GreetingInput.Hydrating(true))
        assertEquals(GreetingMarkState.IDLE, withCast.state.markIntent.state)
    }

    @Test
    fun reducedMotionIsStillAndLooksAtTheUser() {
        val run = Run()
            .send(cast(GreetingMood.IDLE, GreetingMood.IDLE), ReducedMotion(true), Arrived(), PointerOver(GreetingTarget.Actor("a0")))
            .advance(1f)
        run.state.actors.forEach { actor ->
            val intent = run.state.intentFor(actor.id)
            assertEquals(GreetingIntent(look = GreetingTarget.User), intent)
        }
        assertFalse(run.state.active)
        assertTrue(run.state.markIntent.reduceMotion)
    }

    @Test
    fun theSameSeedAndInputsGiveTheSameIntents() {
        fun play() = Run(seed = 42).send(cast(GreetingMood.IDLE, GreetingMood.THINKING, GreetingMood.IDLE), Arrived())
            .advance(2f).send(RunFinished("a1", ok = true)).advance(2f).state
        assertEquals(play(), play())
    }

    @Test
    fun aBusyNeighbourDrawsCuriousLooksOverTime() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.WORKING))
        var curious = false
        repeat(CURIOUS_SECONDS * FPS.toInt()) {
            run.send(Tick(1f / FPS))
            if (run.actor("a0").behavior == GreetingBehavior.CURIOUS) curious = true
        }
        assertTrue(curious)
    }

    @Test
    fun theLineKnowsTheTimeOfDay() {
        assertEquals("Good morning", greetingLine(8))
        assertEquals("Good afternoon", greetingLine(13))
        assertEquals("Good evening", greetingLine(19))
        assertEquals("Still up?", greetingLine(2))
    }

    @Test
    fun aQuietMomentTurnsOneIdleMascotToItsNeighbour() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE, GreetingMood.IDLE), GreetingInput.Quiet).advance(FRAME)
        val turned = run.state.actors.filter { it.behavior == GreetingBehavior.NOTICING }
        assertEquals(1, turned.size)
        val index = run.state.actors.indexOf(turned.single())
        val target = turned.single().target as GreetingTarget.Actor
        assertEquals(1, abs(run.state.actors.indexOfFirst { it.id == target.id } - index))
    }

    @Test
    fun aQuietMomentLeavesBusyMascotsAlone() {
        val run = Run().send(cast(GreetingMood.WORKING, GreetingMood.THINKING), GreetingInput.Quiet)
        assertTrue(run.state.pending.isEmpty())
    }

    @Test
    fun aWaveAndACheerLookHappy() {
        val run = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE), Arrived()).advance(FRAME)
        assertTrue(run.state.intentFor("a0").happy)
        val settled = Run().send(cast(GreetingMood.IDLE, GreetingMood.IDLE)).advance(FRAME)
        assertFalse(settled.state.intentFor("a0").happy)
    }

    @Test
    fun theStatusLineSaysWhatTheAgentsAreDoing() {
        fun line(vararg agents: GreetingStatusAgent) = greetingStatusLine(agents.toList())
        fun ada(mood: GreetingMood, today: Int = 0) = GreetingStatusAgent("Ada", mood, today)
        fun pip(mood: GreetingMood, today: Int = 0) = GreetingStatusAgent("Pip", mood, today)
        assertEquals("Pip needs you", line(ada(GreetingMood.WORKING), pip(GreetingMood.WAITING)))
        assertEquals("Ada hit a snag", line(ada(GreetingMood.ERROR)))
        assertEquals("Ada is on it", line(ada(GreetingMood.THINKING), pip(GreetingMood.IDLE)))
        assertEquals("Ada and Pip are busy", line(ada(GreetingMood.WORKING), pip(GreetingMood.THINKING)))
        assertEquals("Pip's been busy today", line(ada(GreetingMood.IDLE, 1), pip(GreetingMood.IDLE, 5)))
        assertEquals("All quiet. Your agents are resting", line(ada(GreetingMood.IDLE, 1)))
        assertEquals("Your agents will gather here", line())
    }

    private companion object {
        const val FPS = 60f
        const val FRAME = 1f / 60f
        const val SETTLE_SECONDS = 8f
        const val HOVER_ROUNDS = 4
        const val LOW_INTEREST = 0.5f
        const val CURIOUS_SECONDS = 40
    }
}
