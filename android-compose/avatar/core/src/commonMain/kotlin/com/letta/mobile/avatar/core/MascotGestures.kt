package com.letta.mobile.avatar.core

import kotlin.math.sqrt

/**
 * What one pointer step on the mascot meant, as [MascotGestureClassifier] reads it
 * (letta-mobile-te3n1.4). The host maps each onto [MascotEvent]s, the seat's click and a haptic.
 */
enum class MascotGesture {
    /** Nothing to report yet. */
    NONE,

    /** A tap: poke the mascot AND run the seat's click (opening the agent must not wait on the reaction). */
    TAP,

    /** The second tap of a double tap while tricks cool down: a poke, never a second click. */
    NUDGE,

    /** The second tap of a double tap: [MascotGestureClassifier.trick] names the trick. */
    TRICK,

    /** A long press or a slow stroke began petting the mascot. */
    PET_START,

    /** Petting ended, [MascotGestureTune.petReleaseMillis] after the last touch. */
    PET_END,

    /** The pointer (mouse, pen) came over the mascot. Touch never hovers. */
    HOVER_IN,

    /** The pointer left the mascot. */
    HOVER_OUT,
}

/** The classifier's thresholds. Distances in dp, times in ms (the platform's event uptime). */
data class MascotGestureTune(
    /** Movement past this is no longer a tap (the platform's touch slop). */
    val slopDp: Float = 8f,
    /** A press held this long without hurrying is petting. */
    val petDelayMillis: Long = 400L,
    /** Faster than this on average (dp per ms) is a drag or a fling, never petting. */
    val petMaxSpeedDpPerMs: Float = 0.3f,
    /** Petting holds this long after the finger lifts, so a stroke that pauses keeps going. */
    val petReleaseMillis: Long = 600L,
    /** Two taps this close in time are a double tap (the platform's double-tap timeout). */
    val doubleTapMillis: Long = 300L,
    /** ...and this close in space. */
    val doubleTapSlopDp: Float = 32f,
    /** One trick per this long; a double tap inside it is a [MascotGesture.NUDGE]. */
    val trickCooldownMillis: Long = 1_500L,
    /** Tricks cycle 0 until this, then start over. */
    val trickCount: Int = 4,
)

/**
 * Reads raw pointer steps on the mascot's body as hover, tap (poke), double tap (trick), long press
 * or slow stroke (pet) - the host half of the epic's ownership rule (option A): the host interprets
 * gestures, the asset animates. Drags belong to the seat ([cancel] hands a press over to one).
 *
 * Pure and deterministic, the same on Android, desktop and web: positions in dp, time in ms from the
 * events themselves. Each step returns at most one [MascotGesture] and allocates nothing. Timed
 * edges (a still long press, petting's release) come from [tick], which the host calls at
 * [deadline].
 */
class MascotGestureClassifier(private val tune: MascotGestureTune = MascotGestureTune()) {
    /** The trick the last [MascotGesture.TRICK] picked, 0 until [MascotGestureTune.trickCount]. */
    var trick: Int = 0
        private set

    /** Whether the mascot is being petted now (from [MascotGesture.PET_START] to [MascotGesture.PET_END]). */
    var petting: Boolean = false
        private set

    private var hovered = false
    private val press = Press()
    private var petReleaseAt = NEVER
    private var lastTapAt = NEVER_PAST
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTrickAt = NEVER_PAST
    private var nextTrick = 0

    /** One press in progress; reused, never reallocated. */
    private class Press {
        var down = false
        var at = 0L
        var x = 0f
        var y = 0f
        var lastX = 0f
        var lastY = 0f
        var path = 0f

        /** Hurried past the slop: a drag or a fling, so neither a tap nor petting. */
        var escaped = false

        /** Its long-press moment has been ticked; only a move can still start petting. */
        var timed = false
    }

    /** The pointer is ([over]) the mascot or not. */
    fun hover(over: Boolean): MascotGesture {
        if (over == hovered) return MascotGesture.NONE
        hovered = over
        return if (over) MascotGesture.HOVER_IN else MascotGesture.HOVER_OUT
    }

    /** A finger or button went down at ([xDp], [yDp]). A stroke that pauses and resumes keeps petting. */
    fun down(timeMillis: Long, xDp: Float, yDp: Float): MascotGesture {
        press.down = true
        press.at = timeMillis
        press.x = xDp
        press.y = yDp
        press.lastX = xDp
        press.lastY = yDp
        press.path = 0f
        press.escaped = false
        press.timed = false
        petReleaseAt = NEVER
        return MascotGesture.NONE
    }

    /** The pressed pointer moved to ([xDp], [yDp]). */
    fun move(timeMillis: Long, xDp: Float, yDp: Float): MascotGesture {
        if (!press.down) return MascotGesture.NONE
        press.path += distance(xDp - press.lastX, yDp - press.lastY)
        press.lastX = xDp
        press.lastY = yDp
        if (!petting && !press.escaped && beyondSlop() && speed(timeMillis) > tune.petMaxSpeedDpPerMs) press.escaped = true
        return petIfStill(timeMillis)
    }

    /** The press lifted: a tap, a double tap, or the start of petting's release. */
    fun up(timeMillis: Long): MascotGesture {
        if (!press.down) return MascotGesture.NONE
        press.down = false
        if (petting) {
            petReleaseAt = timeMillis + tune.petReleaseMillis
            return MascotGesture.NONE
        }
        val quick = timeMillis - press.at < tune.petDelayMillis
        return if (quick && !press.escaped && !beyondSlop()) tap(timeMillis) else MascotGesture.NONE
    }

    /** Something else took the press (the seat's drag): no tap; petting releases as if lifted. */
    fun cancel(timeMillis: Long): MascotGesture {
        if (!press.down) return MascotGesture.NONE
        press.down = false
        if (petting) petReleaseAt = timeMillis + tune.petReleaseMillis
        return MascotGesture.NONE
    }

    /** Time passed with no event: a still press becomes petting, a lifted one stops. */
    fun tick(timeMillis: Long): MascotGesture {
        if (petting && !press.down && timeMillis >= petReleaseAt) {
            petting = false
            petReleaseAt = NEVER
            return MascotGesture.PET_END
        }
        if (press.down && timeMillis - press.at >= tune.petDelayMillis) press.timed = true
        return petIfStill(timeMillis)
    }

    /** When [tick] next has something to decide, or [NEVER]. */
    fun deadline(): Long = when {
        press.down && !petting && !press.escaped && !press.timed -> press.at + tune.petDelayMillis
        petting && !press.down -> petReleaseAt
        else -> NEVER
    }

    private fun petIfStill(timeMillis: Long): MascotGesture {
        if (!press.down || petting || press.escaped) return MascotGesture.NONE
        if (timeMillis - press.at < tune.petDelayMillis || speed(timeMillis) > tune.petMaxSpeedDpPerMs) return MascotGesture.NONE
        petting = true
        return MascotGesture.PET_START
    }

    private fun tap(timeMillis: Long): MascotGesture {
        val second = timeMillis - lastTapAt <= tune.doubleTapMillis &&
            distance(press.x - lastTapX, press.y - lastTapY) <= tune.doubleTapSlopDp
        if (!second) {
            lastTapAt = timeMillis
            lastTapX = press.x
            lastTapY = press.y
            return MascotGesture.TAP
        }
        // A pair is spent: a third tap starts a new pair instead of pairing with the second.
        lastTapAt = NEVER_PAST
        if (timeMillis - lastTrickAt < tune.trickCooldownMillis) return MascotGesture.NUDGE
        lastTrickAt = timeMillis
        trick = nextTrick
        nextTrick = (nextTrick + 1) % tune.trickCount.coerceAtLeast(1)
        return MascotGesture.TRICK
    }

    private fun beyondSlop(): Boolean = distance(press.lastX - press.x, press.lastY - press.y) > tune.slopDp

    /** Average speed of the press so far, dp per ms. */
    private fun speed(timeMillis: Long): Float = press.path / (timeMillis - press.at).coerceAtLeast(1L)

    private fun distance(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    companion object {
        /** No deadline. */
        const val NEVER: Long = Long.MAX_VALUE

        /** Long enough ago that nothing pairs with it, without overflowing a subtraction. */
        private const val NEVER_PAST: Long = Long.MIN_VALUE / 2
    }
}
