package com.letta.mobile.data.home.greeting

import com.letta.mobile.data.home.greeting.GreetingBehavior.CHEERING
import com.letta.mobile.data.home.greeting.GreetingBehavior.CONCERNED
import com.letta.mobile.data.home.greeting.GreetingBehavior.CURIOUS
import com.letta.mobile.data.home.greeting.GreetingBehavior.GREETING
import com.letta.mobile.data.home.greeting.GreetingBehavior.HUDDLED
import com.letta.mobile.data.home.greeting.GreetingBehavior.NOTICING
import com.letta.mobile.data.home.greeting.GreetingBehavior.SETTLED
import kotlin.math.abs
import kotlin.random.Random

/** Where a mascot stands among the others, and the chance and numbers it reacts with. */
internal class GreetingStage(val index: Int, val ids: List<String>, val random: Random, val tuning: GreetingTuning)

/** A mascot's new state, and the cue its reaction passes on to the neighbours, if any. */
internal class GreetingReaction(val actor: GreetingActor, val passedOn: GreetingCue?)

/** Time passes for one mascot: its reaction runs down, interest habituates and recovers. */
internal fun age(actor: GreetingActor, seconds: Float, tuning: GreetingTuning): GreetingActor {
    val focus = actor.target.takeIf { actor.behavior != SETTLED }
    val interest = buildMap {
        (actor.interest.keys + listOfNotNull(focus)).forEach { key ->
            val now = actor.interest[key] ?: 1f
            val next = if (key == focus) now - seconds / tuning.habituateSeconds else now + seconds / tuning.recoverSeconds
            if (next < 1f) put(key, next.coerceAtLeast(0f))
        }
    }
    val remaining = actor.remaining - seconds
    val ended = remaining <= 0f && actor.behavior != SETTLED && actor.behavior != HUDDLED
    return actor.copy(
        interest = interest,
        remaining = remaining.coerceAtLeast(0f),
        gestureCooldown = (actor.gestureCooldown - seconds).coerceAtLeast(0f),
        behavior = if (ended) SETTLED else actor.behavior,
        target = if (ended) null else actor.target,
        score = if (ended) 0f else actor.score,
        gesture = if (ended) null else actor.gesture,
    )
}

/** With nothing else to do, the connection decides where a mascot rests. */
internal fun baseline(actor: GreetingActor, connection: GreetingConnection): GreetingActor = when {
    actor.behavior == SETTLED && connection.unsettled -> actor.copy(behavior = HUDDLED, target = GreetingTarget.Mark)
    actor.behavior == HUDDLED && !connection.unsettled -> actor.copy(behavior = SETTLED, target = null)
    else -> actor
}

/** The strongest cue this mascot can feel, if it beats what it is doing now by the hysteresis. */
internal fun react(actor: GreetingActor, cues: List<GreetingCue>, stage: GreetingStage): GreetingReaction? {
    val self = GreetingTarget.Actor(actor.id)
    val best = cues.asSequence()
        .filter { (it.only == null || it.only == actor.id) && it.source != self }
        .map { it to it.salience * (actor.interest[it.source] ?: 1f) * nearness(stage, it.source) }
        .maxByOrNull { it.second } ?: return null
    val (cue, score) = best
    val holding = if (actor.behavior == SETTLED || actor.behavior == HUDDLED) 0f else actor.score
    if (score <= holding + stage.tuning.hysteresis) return null
    return adopt(actor, cue, score, stage)
}

private fun adopt(actor: GreetingActor, cue: GreetingCue, score: Float, stage: GreetingStage): GreetingReaction {
    val invite = invitation(cue.kind)
    val wanted = if (actor.mood.busy) NOTICING else invite.behavior
    val gesture = gestureOf(wanted)?.takeIf { actor.gestureCooldown <= 0f }
    val behavior = if (gestureOf(wanted) != null && gesture == null) NOTICING else wanted
    val jitter = stage.tuning.dwellJitter
    val dwell = invite.dwellSeconds * (1f - jitter + 2f * jitter * stage.random.nextFloat())
    val next = actor.copy(
        behavior = behavior,
        target = cue.source,
        remaining = dwell,
        score = score,
        gesture = gesture,
        gestureSerial = if (gesture != null) actor.gestureSerial + 1 else actor.gestureSerial,
        gestureCooldown = if (gesture != null) stage.tuning.gestureCooldownSeconds else actor.gestureCooldown,
    )
    return GreetingReaction(next, passedOn(behavior, actor.id, cue.salience * stage.tuning.spread))
}

/** A wave or a cheer is something the neighbours see. */
private fun passedOn(behavior: GreetingBehavior, id: String, salience: Float): GreetingCue? = when (behavior) {
    GREETING -> GreetingCue(GreetingCueKind.NEIGHBOUR_GREETED, GreetingTarget.Actor(id), salience)
    CHEERING -> GreetingCue(GreetingCueKind.NEIGHBOUR_CHEERED, GreetingTarget.Actor(id), salience)
    else -> null
}

private fun gestureOf(behavior: GreetingBehavior): GreetingGesture? = when (behavior) {
    GREETING -> GreetingGesture.WAVE
    CHEERING -> GreetingGesture.CHEER
    else -> null
}

private class Invitation(val behavior: GreetingBehavior, val dwellSeconds: Float)

private fun invitation(kind: GreetingCueKind): Invitation = when (kind) {
    GreetingCueKind.USER_HELLO, GreetingCueKind.NEIGHBOUR_GREETED -> Invitation(GREETING, HELLO_DWELL)
    GreetingCueKind.NEIGHBOUR_DONE -> Invitation(CHEERING, CHEER_DWELL)
    GreetingCueKind.NEIGHBOUR_TROUBLE -> Invitation(CONCERNED, CONCERN_DWELL)
    GreetingCueKind.NEIGHBOUR_BUSY -> Invitation(CURIOUS, CURIOUS_DWELL)
    GreetingCueKind.POINTED, GreetingCueKind.TAPPED, GreetingCueKind.NEIGHBOUR_CHEERED,
    GreetingCueKind.MARK_CHANGED, GreetingCueKind.USER_TYPING,
    -> Invitation(NOTICING, NOTICE_DWELL)
}

/** Neighbours feel each other more than the far end of the row; the user is always close. */
private fun nearness(stage: GreetingStage, source: GreetingTarget): Float = when (source) {
    GreetingTarget.User -> 1f
    GreetingTarget.Mark -> 1f / (1f + abs(stage.index - (stage.ids.size - 1) / 2f) * MARK_FALLOFF)
    is GreetingTarget.Actor -> {
        val other = stage.ids.indexOf(source.id)
        if (other < 0) 0f else 1f / (1f + (abs(stage.index - other) - 1).coerceAtLeast(0) * PEER_FALLOFF)
    }
}

// How long each reaction holds, before jitter.
private const val HELLO_DWELL = 1.6f
private const val CHEER_DWELL = 1.4f
private const val CONCERN_DWELL = 2.5f
private const val CURIOUS_DWELL = 1.8f
private const val NOTICE_DWELL = 1.2f

private const val MARK_FALLOFF = 0.25f
private const val PEER_FALLOFF = 0.35f
