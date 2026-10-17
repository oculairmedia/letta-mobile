package com.letta.mobile.ui.mascot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.avatar.core.AvatarReaction
import com.letta.mobile.avatar.core.MascotDirector
import com.letta.mobile.avatar.core.MascotEvent
import com.letta.mobile.avatar.core.MascotGesture
import com.letta.mobile.avatar.core.MascotGestureClassifier
import com.letta.mobile.avatar.core.MascotGestureTune
import com.letta.mobile.avatar.core.MascotReactionListener
import com.letta.mobile.avatar.core.ReactionArg
import com.letta.mobile.ui.haptics.Haptics
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.NoHaptics
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * letta-mobile-te3n1.4: the mascot is touchable, the same way on Android, desktop and web. The
 * epic's ownership rule (option A, R/MASCOT.md §2.4): the host reads every gesture - hover, tap,
 * double tap, long press / slow stroke, drag and its velocity - and tells the director, which writes
 * the asset's inputs and triggers; the asset owns every animation. The classifier is shared pure
 * code (avatar/core MascotGestureClassifier); this file only feeds it pointer events and acts on
 * what it reads.
 */

/**
 * What a classified gesture does around one live mascot: the director's event, the seat's click,
 * a haptic, and a nudge to the rest rule so a mascot at rest wakes to play its reaction. Updated
 * in place from composition (like [SeatHandlers]); only [touches] and [petting] are state.
 */
internal class MascotGestureEffects {
    var director: MascotDirector? = null
    var onClick: (() -> Unit)? = null
    var haptics: Haptics = NoHaptics
    var reducedMotion: Boolean = false

    /** Bumped by every gesture: the live surface keeps moving for the rest delay after the last one. */
    var touches: Int by mutableIntStateOf(0)
        private set

    /** Held while the mascot is petted: petting keeps the surface moving for as long as it lasts. */
    var petting: Boolean by mutableStateOf(false)
        private set

    /** Acts on [gesture]; [trick] is the classifier's pick for a [MascotGesture.TRICK]. */
    fun apply(gesture: MascotGesture, trick: Int) {
        when (gesture) {
            MascotGesture.NONE -> return
            MascotGesture.TAP -> {
                // The click first: the reaction must never delay opening the agent.
                onClick?.invoke()
                poke()
            }
            MascotGesture.NUDGE -> poke()
            MascotGesture.TRICK -> trick(trick)
            MascotGesture.PET_START, MascotGesture.PET_END -> pet(gesture == MascotGesture.PET_START)
            MascotGesture.HOVER_IN -> director?.onEvent(HOVER_ON)
            MascotGesture.HOVER_OUT -> director?.onEvent(HOVER_OFF)
        }
        touches++
    }

    /** A press landed on the mascot: wake the surface before anything is classified. */
    fun pressed() {
        touches++
    }

    private fun poke() {
        director?.onEvent(MascotEvent.Poke)
        // A confirm tick: it stays under reduced motion.
        haptics.play(LettaHapticCue.MascotTap)
    }

    private fun trick(id: Int) {
        director?.onEvent(TRICKS[id.mod(TRICKS.size)])
        felt(LettaHapticCue.GestureThreshold)
    }

    private fun pet(active: Boolean) {
        petting = active
        director?.onEvent(if (active) PET_ON else PET_OFF)
    }

    /** A haptic that accompanies motion: none under reduced motion. */
    private fun felt(cue: LettaHapticCue) {
        if (!reducedMotion) haptics.play(cue)
    }

    private companion object {
        val HOVER_ON = MascotEvent.Hover(hovered = true)
        val HOVER_OFF = MascotEvent.Hover(hovered = false)
        val PET_ON = MascotEvent.Petting(active = true)
        val PET_OFF = MascotEvent.Petting(active = false)
        val TRICKS = Array(MascotGestureTune().trickCount) { MascotEvent.DoubleTap(trick = it) }
    }
}

/**
 * Reads hover, taps, double taps and petting on the mascot's body for [effects]. Drags stay the
 * seat's ([seatDrag]): events are read after every other handler ([PointerEventPass.Final]), so a
 * press the seat's drag takes is handed over, not tapped. A tap's release is consumed, as a click's is.
 */
internal fun Modifier.mascotGestures(effects: MascotGestureEffects): Modifier =
    pointerInput(effects) { readMascotGestures(effects) }

/** The last event's time, which the timer measures its waits from. */
private class GestureClock {
    var nowMillis = 0L
}

private suspend fun PointerInputScope.readMascotGestures(effects: MascotGestureEffects) {
    val classifier = MascotGestureClassifier(
        MascotGestureTune(slopDp = viewConfiguration.touchSlop / density, doubleTapMillis = viewConfiguration.doubleTapTimeoutMillis),
    )
    val clock = GestureClock()
    // Woken on every press and release; conflated, so signalling never queues or allocates.
    val wake = Channel<Unit>(Channel.CONFLATED)
    coroutineScope {
        launch { tickDeadlines(classifier, effects, clock, wake) }
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: continue
                clock.nowMillis = change.uptimeMillis
                if (event.type == PointerEventType.Press) effects.pressed()
                val gesture = classifier.read(event.type, change, 1f / density)
                if (gesture == MascotGesture.TAP || gesture == MascotGesture.NUDGE || gesture == MascotGesture.TRICK) change.consume()
                effects.apply(gesture, classifier.trick)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) wake.trySend(Unit)
            }
        }
    }
}

/** One pointer event as a classifier step; [dpPerPx] turns positions into the classifier's dp. */
private fun MascotGestureClassifier.read(type: PointerEventType, change: PointerInputChange, dpPerPx: Float): MascotGesture {
    val t = change.uptimeMillis
    val x = change.position.x * dpPerPx
    val y = change.position.y * dpPerPx
    return when (type) {
        // Touch never hovers: a finger is not a pointer resting over the character.
        PointerEventType.Enter -> if (change.type == PointerType.Touch) MascotGesture.NONE else hover(true)
        PointerEventType.Exit -> hover(false)
        PointerEventType.Press -> down(t, x, y)
        // Consumed by the time it reaches this pass: the seat's drag has it.
        PointerEventType.Move -> if (change.isConsumed) cancel(t) else move(t, x, y)
        PointerEventType.Release -> up(t)
        else -> MascotGesture.NONE
    }
}

/**
 * Runs the classifier's timed edges - a still press becoming petting, petting's release - at its
 * [MascotGestureClassifier.deadline], measured from the last event. Idle, it waits on [wake].
 */
private suspend fun tickDeadlines(
    classifier: MascotGestureClassifier,
    effects: MascotGestureEffects,
    clock: GestureClock,
    wake: Channel<Unit>,
) {
    while (true) {
        wake.receive()
        var deadline = classifier.deadline()
        while (deadline != MascotGestureClassifier.NEVER) {
            delay((deadline - clock.nowMillis).coerceAtLeast(1L))
            if (deadline > clock.nowMillis) clock.nowMillis = deadline
            effects.apply(classifier.tick(clock.nowMillis), classifier.trick)
            deadline = classifier.deadline()
        }
    }
}


/**
 * How hard the seat's body squashes for [reaction] (0..1), or 0 for none. LAND is not here: a hop's
 * landing squashes itself, and a drop squashes at its release speed ([MascotDragFeel.putDown]).
 */
internal fun bodyImpactOf(reaction: AvatarReaction): Float = when (reaction) {
    AvatarReaction.POKE -> POKE_IMPACT
    AvatarReaction.DIZZY -> DIZZY_IMPACT
    AvatarReaction.TRICK -> TRICK_IMPACT
    AvatarReaction.SENT -> SENT_IMPACT
    AvatarReaction.RUN_DONE -> RUN_DONE_IMPACT
    AvatarReaction.WAKE -> WAKE_IMPACT
    else -> 0f
}

private const val POKE_IMPACT = 0.5f
private const val DIZZY_IMPACT = 0.9f
private const val TRICK_IMPACT = 0.7f

/** A small catch-dip as the message leaves. */
private const val SENT_IMPACT = 0.25f

/** The finished-run hop: a deep squash that springs up past standing. */
private const val RUN_DONE_IMPACT = 0.6f
private const val WAKE_IMPACT = 0.35f

/**
 * A transported character's body answering its director's reactions (te3n1.4 over te3n1.3's
 * springs): each reaction the director fires squashes the body by [bodyImpactOf] and restarts its
 * frame driver. Nothing under reduced motion, nor when the rig animates the body itself.
 */
internal class FlightReactions(
    private val flight: MascotTransport.Flight,
    private val rigCarriesBody: () -> Boolean,
) : MascotReactionListener {
    var reducedMotion: Boolean = false

    override fun onReaction(reaction: AvatarReaction, arg: ReactionArg) {
        if (reducedMotion || rigCarriesBody()) return
        val impact = bodyImpactOf(reaction)
        if (impact > 0f) flight.impulse(impact)
    }
}

/**
 * What a seat drag tells the character: picked up (the director holds DRAGGED), and put down at
 * its release speed (LAND with that impact, a squash on the body, a haptic). Updated in place.
 */
internal class MascotDragFeel(private val flight: MascotTransport.Flight) {
    var director: MascotDirector? = null
    var haptics: Haptics = NoHaptics
    var reducedMotion: Boolean = false

    fun pickedUp() {
        director?.onEvent(MascotEvent.DragStarted)
        if (!reducedMotion) haptics.play(LettaHapticCue.GestureStart)
    }

    /** Put down while moving at ([vxDp], [vyDp]) dp/s. */
    fun putDown(vxDp: Float, vyDp: Float) {
        val impact = (sqrt(vxDp * vxDp + vyDp * vyDp) / flight.body.tune.impactSpeedDp).coerceIn(0f, 1f)
        director?.onEvent(MascotEvent.DragEnded(impact))
        if (reducedMotion) return
        flight.impulse(impact)
        haptics.play(LettaHapticCue.GestureEnd)
    }
}
