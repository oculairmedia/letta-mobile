package com.letta.mobile.data.voice.activation

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How a key or button drives the loop. */
enum class ActivationKeyMode {
    /** A tap toggles listening; a hold of at least the hold threshold is push-to-talk. */
    AUTO,

    /** Listening lasts exactly as long as the key is held. */
    HOLD,

    /** Each press toggles listening. */
    TOGGLE,
}

/** A monotonic timestamp of a key gesture, measured from any fixed origin. */
@JvmInline
value class KeyTime(
    /** Time since the origin. */
    val sinceOrigin: Duration,
)

/** One key or button gesture. Platforms translate their own key events into these. */
sealed interface KeyGesture {
    /** The key went down. */
    data class Press(
        /** When. */
        val at: KeyTime,
    ) : KeyGesture

    /** The key came up. */
    data class Release(
        /** When. */
        val at: KeyTime,
    ) : KeyGesture

    /** The person cancelled (Escape). */
    data object Cancel : KeyGesture
}

/** What a gesture means for the loop. */
enum class KeyDecision {
    /** Start listening. */
    START,

    /** The person finished their turn. */
    END_TURN,

    /** Discard the turn and stop. */
    DISCARD,

    /** Nothing changes. */
    NONE,
}

/** What the classifier remembers between gestures. */
data class KeyClassifierState(
    /** Whether the key has listening on. */
    val active: Boolean = false,
    /** When the current press began; null when the key is up or the press ended a turn. */
    val pressedAt: KeyTime? = null,
    /** Whether the key is physically down, independent of [active]; repeats are ignored until it comes up. */
    val keyDown: Boolean = false,
)

/** A classification: the next state and what it means. */
data class KeyStep(
    /** The state after the gesture. */
    val state: KeyClassifierState,
    /** What the gesture means. */
    val decision: KeyDecision,
)

/**
 * Pure classifier from key gestures to loop decisions for one [mode]. In [ActivationKeyMode.AUTO]
 * a press held for [holdThreshold] or longer is push-to-talk; a shorter press toggles.
 */
class ActivationKeyClassifier(
    private val mode: ActivationKeyMode,
    private val holdThreshold: Duration = DEFAULT_HOLD_THRESHOLD,
) {
    /** Classifies [gesture] given the previous [state]. */
    fun classify(state: KeyClassifierState, gesture: KeyGesture): KeyStep = when (gesture) {
        is KeyGesture.Press -> press(state, gesture.at)
        is KeyGesture.Release -> release(state, gesture.at)
        KeyGesture.Cancel -> cancel(state)
    }

    /** A press while the key is already down (key repeat) changes nothing, whatever it did before. */
    private fun press(state: KeyClassifierState, at: KeyTime): KeyStep =
        if (state.keyDown) KeyStep(state, KeyDecision.NONE) else pressDown(state, at)

    private fun pressDown(state: KeyClassifierState, at: KeyTime): KeyStep = when {
        !state.active -> KeyStep(KeyClassifierState(active = true, pressedAt = at, keyDown = true), KeyDecision.START)
        mode == ActivationKeyMode.HOLD -> KeyStep(state.copy(keyDown = true), KeyDecision.NONE)
        else -> KeyStep(KeyClassifierState(keyDown = true), KeyDecision.END_TURN)
    }

    /** The key comes up; a release after a press that already ended the turn changes nothing more. */
    private fun release(state: KeyClassifierState, at: KeyTime): KeyStep {
        val up = state.copy(keyDown = false)
        val pressedAt = up.pressedAt ?: return KeyStep(up, KeyDecision.NONE)
        val held = at.sinceOrigin - pressedAt.sinceOrigin
        return if (endsTurnOnRelease(held)) {
            KeyStep(KeyClassifierState(), KeyDecision.END_TURN)
        } else {
            KeyStep(up.copy(pressedAt = null), KeyDecision.NONE)
        }
    }

    private fun endsTurnOnRelease(held: Duration): Boolean = when (mode) {
        ActivationKeyMode.HOLD -> true
        ActivationKeyMode.TOGGLE -> false
        ActivationKeyMode.AUTO -> held >= holdThreshold
    }

    private fun cancel(state: KeyClassifierState): KeyStep =
        KeyStep(KeyClassifierState(keyDown = state.keyDown), if (state.active) KeyDecision.DISCARD else KeyDecision.NONE)

    /** Defaults. */
    companion object {
        /** A press at least this long is push-to-talk in [ActivationKeyMode.AUTO]. */
        val DEFAULT_HOLD_THRESHOLD: Duration = 300.milliseconds
    }
}
