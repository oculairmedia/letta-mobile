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

/**
 * How one mascot lives through a tick: it ages ([age]), rests where the connection puts it
 * ([baseline]) and maybe reacts to the strongest cue it feels ([react]). What each cue invites, which
 * reactions are gestures and which pass on to the neighbours are lookups, not branches.
 */
internal object GreetingReactions {
    /** Time passes for one mascot: its reaction runs down, interest habituates and recovers. */
    fun age(actor: GreetingActor, seconds: Float, tuning: GreetingTuning): GreetingActor {
        val aged = actor.copy(
            interest = habituated(actor, seconds, tuning),
            remaining = (actor.remaining - seconds).coerceAtLeast(0f),
            gestureCooldown = (actor.gestureCooldown - seconds).coerceAtLeast(0f),
        )
        val ended = actor.remaining <= seconds && !actor.behavior.resting
        return if (ended) aged.copy(behavior = SETTLED, target = null, score = 0f, gesture = null) else aged
    }

    /** With nothing else to do, the connection decides where a mascot rests: huddled by the mark, or settled. */
    fun baseline(actor: GreetingActor, connection: GreetingConnection): GreetingActor {
        val rest = if (connection.unsettled) HUDDLED else SETTLED
        val moves = actor.behavior.resting && actor.behavior != rest
        return if (moves) actor.copy(behavior = rest, target = RESTING_TARGET[rest]) else actor
    }

    /** The strongest cue this mascot can feel, if it beats what it is doing now by the hysteresis. */
    fun react(actor: GreetingActor, cues: List<GreetingCue>, stage: GreetingStage): GreetingReaction? {
        val (cue, score) = cues.asSequence()
            .filter { it.reaches(actor) }
            .map { it to it.salience * (actor.interest[it.source] ?: 1f) * nearness(stage, it.source) }
            .maxByOrNull { it.second } ?: return null
        val holding = if (actor.behavior.resting) 0f else actor.score
        return if (score > holding + stage.tuning.hysteresis) adopt(actor, cue, score, stage) else null
    }

    private fun habituated(actor: GreetingActor, seconds: Float, tuning: GreetingTuning): Map<GreetingTarget, Float> {
        val focus = actor.target.takeIf { actor.behavior != SETTLED }
        return buildMap {
            (actor.interest.keys + listOfNotNull(focus)).forEach { key ->
                val now = actor.interest[key] ?: 1f
                val next = if (key == focus) now - seconds / tuning.habituateSeconds else now + seconds / tuning.recoverSeconds
                if (next < 1f) put(key, next.coerceAtLeast(0f))
            }
        }
    }

    /** For this mascot (everyone's, or addressed to it) and from someone else. */
    private fun GreetingCue.reaches(actor: GreetingActor): Boolean =
        (only ?: actor.id) == actor.id && source != GreetingTarget.Actor(actor.id)

    private fun adopt(actor: GreetingActor, cue: GreetingCue, score: Float, stage: GreetingStage): GreetingReaction {
        val invite = INVITATIONS[cue.kind] ?: NOTICE
        // A busy mascot only looks; a gesture still cooling down is only a look too.
        val wanted = if (actor.mood.busy) NOTICING else invite.behavior
        val gesture = GESTURES[wanted]?.takeIf { actor.gestureCooldown <= 0f }
        val behavior = if (gesture == null && wanted in GESTURES) NOTICING else wanted
        val jitter = stage.tuning.dwellJitter
        val next = actor.copy(
            behavior = behavior,
            target = cue.source,
            remaining = invite.dwellSeconds * (1f - jitter + 2f * jitter * stage.random.nextFloat()),
            score = score,
            gesture = gesture,
        )
        val played = if (gesture == null) next else next.played(stage.tuning)
        return GreetingReaction(played, passedOn(behavior, actor.id, cue.salience * stage.tuning.spread))
    }

    /** A gesture was played: count it for the UI and start its cooldown. */
    private fun GreetingActor.played(tuning: GreetingTuning): GreetingActor =
        copy(gestureSerial = gestureSerial + 1, gestureCooldown = tuning.gestureCooldownSeconds)

    /** A wave or a cheer is something the neighbours see. */
    private fun passedOn(behavior: GreetingBehavior, id: String, salience: Float): GreetingCue? =
        PASSED_ON[behavior]?.let { kind -> GreetingCue(kind, GreetingTarget.Actor(id), salience) }

    /** Neighbours feel each other more than the far end of the row; the user is always close. */
    private fun nearness(stage: GreetingStage, source: GreetingTarget): Float = when (source) {
        GreetingTarget.User -> 1f
        GreetingTarget.Mark -> 1f / (1f + abs(stage.index - (stage.ids.size - 1) / 2f) * MARK_FALLOFF)
        is GreetingTarget.Actor -> peerNearness(stage, stage.ids.indexOf(source.id))
    }

    /** Adjacent mascots feel each other fully; each place further away a little less. Off stage: not at all. */
    private fun peerNearness(stage: GreetingStage, other: Int): Float {
        val apart = (abs(stage.index - other) - 1).coerceAtLeast(0)
        return if (other < 0) 0f else 1f / (1f + apart * PEER_FALLOFF)
    }

    private class Invitation(val behavior: GreetingBehavior, val dwellSeconds: Float)

    // How long each reaction holds, before jitter.
    private const val HELLO_DWELL = 1.6f
    private const val CHEER_DWELL = 1.4f
    private const val CONCERN_DWELL = 2.5f
    private const val CURIOUS_DWELL = 1.8f
    private const val NOTICE_DWELL = 1.2f

    private const val MARK_FALLOFF = 0.25f
    private const val PEER_FALLOFF = 0.35f

    /** What a cue not listed in [INVITATIONS] invites: a look. */
    private val NOTICE = Invitation(NOTICING, NOTICE_DWELL)

    /** What each cue invites, and for how long. */
    private val INVITATIONS: Map<GreetingCueKind, Invitation> = mapOf(
        GreetingCueKind.USER_HELLO to Invitation(GREETING, HELLO_DWELL),
        GreetingCueKind.NEIGHBOUR_GREETED to Invitation(GREETING, HELLO_DWELL),
        GreetingCueKind.NEIGHBOUR_DONE to Invitation(CHEERING, CHEER_DWELL),
        GreetingCueKind.NEIGHBOUR_TROUBLE to Invitation(CONCERNED, CONCERN_DWELL),
        GreetingCueKind.NEIGHBOUR_BUSY to Invitation(CURIOUS, CURIOUS_DWELL),
    )

    /** The behaviours that play a one-shot, and which. */
    private val GESTURES: Map<GreetingBehavior, GreetingGesture> =
        mapOf(GREETING to GreetingGesture.WAVE, CHEERING to GreetingGesture.CHEER)

    /** The behaviours the neighbours react to in turn, as which cue. */
    private val PASSED_ON: Map<GreetingBehavior, GreetingCueKind> =
        mapOf(GREETING to GreetingCueKind.NEIGHBOUR_GREETED, CHEERING to GreetingCueKind.NEIGHBOUR_CHEERED)

    /** Where a resting behaviour looks: the huddle at the mark, a settled mascot at nothing in particular. */
    private val RESTING_TARGET: Map<GreetingBehavior, GreetingTarget> = mapOf(HUDDLED to GreetingTarget.Mark)
}
