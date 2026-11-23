package com.letta.mobile.data.home.greeting

import kotlin.random.Random

/**
 * The greeting's numbers. Interest follows the same habituation law as the mascot's gaze: it falls
 * while a target is attended and recovers while it is not, which is what ends a back-and-forth.
 */
data class GreetingTuning(
    /** Seconds of attention that use a target's interest up completely. */
    val habituateSeconds: Float = 4f,
    /** Seconds away from a target that restore its interest completely. */
    val recoverSeconds: Float = 12f,
    /** How much stronger a new stimulus must be than the current one to take over. */
    val hysteresis: Float = 0.1f,
    /** A reaction passed on to a neighbour keeps this share of its strength. */
    val spread: Float = 0.5f,
    /** A cue weaker than this is dropped, so a chain of reactions dies out on its own. */
    val minSalience: Float = 0.2f,
    /** Dwell times vary by up to this share either way, so two mascots never move in step. */
    val dwellJitter: Float = 0.3f,
    /** Seconds before the same mascot may wave or cheer again. */
    val gestureCooldownSeconds: Float = 4f,
    /** How often, on average, a busy mascot catches a neighbour's eye (per second). */
    val busyCuesPerSecond: Float = 1f / 6f,
)

/**
 * The greeting's state machine: a pure reducer from [GreetingInput] to [GreetingState]. Inputs that
 * are moments (a mood change, a hover, an arrival) become cues; each [GreetingInput.Tick] lets every
 * mascot weigh the cues against what it is doing and maybe react. A reaction is itself a cue for the
 * neighbours on the next tick, weaker each time it is passed on, so behaviour spreads and dies out
 * without any scripted sequence. Deterministic: time comes only from ticks and chance only from the
 * [Random] passed in.
 */
object GreetingPlay {
    fun reduce(
        state: GreetingState,
        input: GreetingInput,
        random: Random,
        tuning: GreetingTuning = GreetingTuning(),
    ): GreetingState = when (input) {
        is GreetingInput.Tick -> tick(state, input.seconds, random, tuning)
        is GreetingInput.ReducedMotion -> reducedMotion(state, input.active)
        GreetingInput.Quiet -> if (state.reducedMotion) state else state.queue(listOfNotNull(idleGlance(state, random)))
        // Under reduced motion moments raise no cues; the levels they carry are still kept.
        else -> record(if (state.reducedMotion) state else state.queue(cuesFor(state, input)), input)
    }

    /** Records the levels an input carries (who is in the cast, the connection, the pointer). */
    private fun record(state: GreetingState, input: GreetingInput): GreetingState = when (input) {
        is GreetingInput.Cast -> state.copy(actors = input.members.map { member -> actorFor(state, member) })
        is GreetingInput.Arrived -> if (state.booted) state else state.copy(booted = true, markBootSerial = state.markBootSerial + 1)
        is GreetingInput.PointerOver -> state.copy(pointer = input.target)
        is GreetingInput.Typing -> state.copy(typing = input.active)
        is GreetingInput.Hydrating -> state.copy(hydrating = input.active)
        is GreetingInput.Connection -> connection(state, input.connection)
        else -> state
    }

    private fun actorFor(state: GreetingState, member: GreetingCastMember): GreetingActor =
        state.actors.firstOrNull { it.id == member.id }?.copy(mood = member.mood) ?: GreetingActor(member.id, member.mood)

    private fun connection(state: GreetingState, next: GreetingConnection): GreetingState {
        val locked = state.connection.unsettled && next == GreetingConnection.CONNECTED
        return state.copy(
            connection = next,
            markSuccessSerial = if (locked) state.markSuccessSerial + 1 else state.markSuccessSerial,
        )
    }

    private fun reducedMotion(state: GreetingState, active: Boolean): GreetingState {
        if (!active) return state.copy(reducedMotion = false)
        // Calm: everyone settles where they stand and nothing waits to play.
        val calm = state.actors.map { GreetingActor(id = it.id, mood = it.mood, gestureSerial = it.gestureSerial) }
        return state.copy(reducedMotion = true, actors = calm, pending = emptyList())
    }

    private fun GreetingState.queue(cues: List<GreetingCue>): GreetingState = if (cues.isEmpty()) this else copy(pending = pending + cues)

    private fun tick(state: GreetingState, seconds: Float, random: Random, tuning: GreetingTuning): GreetingState {
        if (state.reducedMotion || !(seconds > 0f)) return state
        val cues = state.pending + ambientCues(state, seconds, random, tuning)
        val ids = state.actors.map { it.id }
        val passedOn = mutableListOf<GreetingCue>()
        val actors = state.actors.mapIndexed { index, actor ->
            val aged = baseline(age(actor, seconds, tuning), state.connection)
            react(aged, cues, GreetingStage(index, ids, random, tuning))?.let { reaction ->
                reaction.passedOn?.takeIf { it.salience >= tuning.minSalience }?.let(passedOn::add)
                reaction.actor
            } ?: aged
        }
        return state.copy(actors = actors, pending = passedOn)
    }

    /** A busy mascot now and then catches a neighbour's eye. */
    private fun ambientCues(state: GreetingState, seconds: Float, random: Random, tuning: GreetingTuning): List<GreetingCue> {
        if (state.actors.size < 2) return emptyList()
        return state.actors.filter { it.mood.busy && random.nextFloat() < tuning.busyCuesPerSecond * seconds }.map { busyAmbientCue(it.id) }
    }
}
