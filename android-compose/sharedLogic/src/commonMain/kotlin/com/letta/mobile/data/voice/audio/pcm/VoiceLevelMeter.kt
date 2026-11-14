package com.letta.mobile.data.voice.audio.pcm

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns loudness readings ([PcmLevels.level]: 0 at -50 dBFS and below, 1 at full scale) into a
 * smoothed level from 0 to 1 for a visual: a room's noise reads 0, ordinary speech about half,
 * loud speech near 1. It rises quickly ([attackMillis]) and falls slowly ([releaseMillis]), and
 * once within [SETTLE] of where the readings put it, it sits there exactly, so silence ends at 0
 * rather than creeping toward it.
 *
 * A negative reading means the source is muted (a half-duplex ear ignoring the microphone while
 * the app speaks): the level reads 0 at once.
 *
 * Numbers only, and no allocation: one instance per signal, stepped by one coroutine.
 */
internal class VoiceLevelMeter(
    /** Readings at or below this are silence (about -44 dBFS: a quiet room). */
    private val floor: Float = DEFAULT_FLOOR,
    /** Readings at or above this are full (about -10 dBFS: a raised voice close to the microphone). */
    private val ceiling: Float = DEFAULT_CEILING,
    /** Time constant while the level rises. */
    private val attackMillis: Float = DEFAULT_ATTACK_MILLIS,
    /** Time constant while the level falls. */
    private val releaseMillis: Float = DEFAULT_RELEASE_MILLIS,
) {
    init {
        require(ceiling > floor) { "The ceiling must be above the floor" }
        require(attackMillis > 0f && releaseMillis > 0f) { "Time constants must be positive" }
    }

    /** The current smoothed level, 0..1. */
    var value: Float = 0f
        private set

    /** Where [reading] puts the level once it has settled: 0..1; a muted (negative) reading is 0. */
    fun target(reading: Float): Float {
        if (reading.isNaN() || reading < 0f) return 0f
        return ((reading - floor) / (ceiling - floor)).coerceIn(0f, 1f)
    }

    /** Steps the level toward [reading] over [elapsedMillis]; returns the new [value]. */
    fun update(reading: Float, elapsedMillis: Long): Float {
        val target = target(reading)
        if (reading < 0f) {
            value = 0f
            return value
        }
        val tau = if (target > value) attackMillis else releaseMillis
        val step = 1f - exp(-elapsedMillis.coerceAtLeast(0L).toFloat() / tau)
        value += (target - value) * step
        if (abs(target - value) < SETTLE) value = target
        return value
    }

    /** Whether the level already sits where [reading] puts it (nothing left to animate). */
    fun settled(reading: Float): Boolean = value == target(reading)

    /** Back to silence. */
    fun reset() {
        value = 0f
    }

    /** The meter's defaults. */
    companion object {
        /** A reading the source reports while it is muted. */
        const val MUTED: Float = -1f

        /** How often a smoothed level is stepped and published at most: about 30 times a second. */
        const val INTERVAL_MILLIS: Long = 33L

        private const val DEFAULT_FLOOR = 0.12f
        private const val DEFAULT_CEILING = 0.8f
        private const val DEFAULT_ATTACK_MILLIS = 45f
        private const val DEFAULT_RELEASE_MILLIS = 160f
        private const val SETTLE = 0.01f
    }
}
