package com.letta.mobile.avatar.core

import kotlin.jvm.JvmInline
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What one pointer step on the mascot meant, as [MascotGestureClassifier] reads it
 * (letta-mobile-te3n1.4). The host maps each onto [MascotEvent]s, the seat's click and a haptic.
 */
enum class MascotGesture(
    /** The gesture ended a press as a tap of some kind: the host consumes that release, as a click would. */
    val isTap: Boolean = false,
) {
    /** Nothing to report yet. */
    NONE,

    /** A tap: poke the mascot AND run the seat's click (opening the agent must not wait on the reaction). */
    TAP(isTap = true),

    /** The second tap of a double tap while tricks cool down: a poke, never a second click. */
    NUDGE(isTap = true),

    /** The second tap of a double tap: [MascotGestureClassifier.trick] names the trick. */
    TRICK(isTap = true),

    /** A long press or a slow stroke began petting the mascot. */
    PET_START,

    /** Petting ended, [MascotGestureTune.petRelease] after the last touch. */
    PET_END,

    /** The pointer (mouse, pen) came over the mascot. Touch never hovers. */
    HOVER_IN,

    /** The pointer left the mascot. */
    HOVER_OUT,
}

/** A pointer event's time, on the platform's event clock. */
@JvmInline
value class PointerTime(val millis: Long) : Comparable<PointerTime> {
    operator fun minus(earlier: PointerTime): Duration = (millis - earlier.millis).milliseconds

    operator fun plus(wait: Duration): PointerTime = PointerTime(millis + wait.inWholeMilliseconds)

    override fun compareTo(other: PointerTime): Int = millis.compareTo(other.millis)

    companion object {
        /** No deadline: later than every event. */
        val NEVER: PointerTime = PointerTime(Long.MAX_VALUE)

        /** Long enough ago that nothing pairs with it, without overflowing a subtraction. */
        internal val LONG_AGO: PointerTime = PointerTime(Long.MIN_VALUE / 2)
    }
}

/** A point on the mascot in dp, two floats in one word so a pointer step never allocates. */
@JvmInline
value class GesturePoint private constructor(private val packed: Long) {
    constructor(xDp: Float, yDp: Float) : this((xDp.toRawBits().toLong() shl 32) or (yDp.toRawBits().toLong() and LOW_BITS))

    val xDp: Float get() = Float.fromBits((packed ushr 32).toInt())
    val yDp: Float get() = Float.fromBits((packed and LOW_BITS).toInt())

    /** Straight-line distance to [other], dp. */
    fun distanceTo(other: GesturePoint): Float {
        val dx = xDp - other.xDp
        val dy = yDp - other.yDp
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val LOW_BITS = 0xFFFF_FFFFL
    }
}

/** How fast a press travels, dp per millisecond. */
@JvmInline
value class GestureSpeed(val dpPerMs: Float) : Comparable<GestureSpeed> {
    override fun compareTo(other: GestureSpeed): Int = dpPerMs.compareTo(other.dpPerMs)

    companion object {
        /** [pathDp] travelled over [elapsed] (at least a millisecond). */
        fun of(pathDp: Float, elapsed: Duration): GestureSpeed =
            GestureSpeed(pathDp / elapsed.inWholeMilliseconds.coerceAtLeast(1L))
    }
}

/** The classifier's thresholds. */
data class MascotGestureTune(
    /** Movement past this (dp) is no longer a tap: the platform's touch slop. */
    val slopDp: Float = 8f,
    /** A press held this long without hurrying is petting. */
    val petDelay: Duration = 400.milliseconds,
    /** Faster than this on average is a drag or a fling, never petting. */
    val petMaxSpeed: GestureSpeed = GestureSpeed(0.3f),
    /** Petting holds this long after the finger lifts, so a stroke that pauses keeps going. */
    val petRelease: Duration = 600.milliseconds,
    /** Two taps this close in time are a double tap: the platform's double-tap timeout. */
    val doubleTap: Duration = 300.milliseconds,
    /** ...and this close in space (dp). */
    val doubleTapSlopDp: Float = 32f,
    /** One trick per this long; a double tap inside it is a [MascotGesture.NUDGE]. */
    val trickCooldown: Duration = 1_500.milliseconds,
    /** Tricks cycle 0 until this, then start over. */
    val trickCount: Int = 4,
)

/**
 * Reads raw pointer steps on the mascot's body as hover, tap (poke), double tap (trick), long press
 * or slow stroke (pet) - the host half of the epic's ownership rule (option A): the host interprets
 * gestures, the asset animates. Drags belong to the seat ([cancel] hands a press over to one).
 *
 * A small state machine ([Phase]), pure and deterministic, the same on Android, desktop and web:
 * positions in dp, time from the events themselves. Each step returns at most one [MascotGesture]
 * and allocates nothing. Timed edges (a still long press, petting's release) come from [tick],
 * which the host calls at [deadline].
 */
class MascotGestureClassifier(private val tune: MascotGestureTune = MascotGestureTune()) {
    /** Where a press stands. */
    private enum class Phase {
        /** No press, no petting. */
        IDLE,

        /** Down, still a tap or the start of petting. */
        PRESSED,

        /** Down past the long-press moment without petting; only a slow move can still pet. */
        TIMED,

        /** Down and hurried past the slop: a drag or a fling, neither a tap nor petting. */
        ESCAPED,

        /** Down and petting. */
        PETTING,

        /** Lifted while petting; it ends at the release deadline unless the stroke resumes. */
        LETTING_GO,
    }

    /** The trick the last [MascotGesture.TRICK] picked, 0 until [MascotGestureTune.trickCount]. */
    val trick: Int get() = taps.trick

    /** Whether the mascot is being petted now (from [MascotGesture.PET_START] to [MascotGesture.PET_END]). */
    val petting: Boolean get() = phase == Phase.PETTING || phase == Phase.LETTING_GO

    private var phase = Phase.IDLE
    private var hovered = false
    private val press = Press()
    private val taps = TapPairing(tune)

    /** One press in progress; reused, never reallocated. */
    private class Press {
        var at = PointerTime(0L)
        var origin = GesturePoint(0f, 0f)
        var last = GesturePoint(0f, 0f)
        var pathDp = 0f
        var releaseAt = PointerTime.NEVER

        fun start(time: PointerTime, point: GesturePoint) {
            at = time
            origin = point
            last = point
            pathDp = 0f
            releaseAt = PointerTime.NEVER
        }

        fun moveTo(point: GesturePoint) {
            pathDp += last.distanceTo(point)
            last = point
        }

        fun speed(time: PointerTime): GestureSpeed = GestureSpeed.of(pathDp, time - at)
    }

    /** The pointer is ([over]) the mascot or not. */
    fun hover(over: Boolean): MascotGesture {
        if (over == hovered) return MascotGesture.NONE
        hovered = over
        return if (over) MascotGesture.HOVER_IN else MascotGesture.HOVER_OUT
    }

    /** A finger or button went down at [point]. A stroke that pauses and resumes keeps petting. */
    fun down(time: PointerTime, point: GesturePoint): MascotGesture {
        press.start(time, point)
        phase = if (petting) Phase.PETTING else Phase.PRESSED
        return MascotGesture.NONE
    }

    /** The pressed pointer moved to [point]. */
    fun move(time: PointerTime, point: GesturePoint): MascotGesture {
        if (!undecided()) return MascotGesture.NONE
        press.moveTo(point)
        if (hurried(time)) {
            phase = Phase.ESCAPED
            return MascotGesture.NONE
        }
        return petIfHeldLongEnough(time)
    }

    /** The press lifted: a tap, a double tap, or the start of petting's release. */
    fun up(time: PointerTime): MascotGesture = when (phase) {
        Phase.PRESSED -> release(time)
        Phase.PETTING -> letGo(time)
        Phase.TIMED, Phase.ESCAPED -> idle()
        Phase.IDLE, Phase.LETTING_GO -> MascotGesture.NONE
    }

    /** Something else took the press (the seat's drag): no tap; petting releases as if lifted. */
    fun cancel(time: PointerTime): MascotGesture = when (phase) {
        Phase.PETTING -> letGo(time)
        Phase.PRESSED, Phase.TIMED, Phase.ESCAPED -> idle()
        Phase.IDLE, Phase.LETTING_GO -> MascotGesture.NONE
    }

    /** Time passed with no event: a still press becomes petting, a lifted one stops. */
    fun tick(time: PointerTime): MascotGesture = when (phase) {
        Phase.PRESSED -> longPressMoment(time)
        Phase.TIMED -> petIfHeldLongEnough(time)
        Phase.LETTING_GO -> endPettingAt(time)
        Phase.IDLE, Phase.ESCAPED, Phase.PETTING -> MascotGesture.NONE
    }

    /** When [tick] next has something to decide, or [PointerTime.NEVER]. */
    fun deadline(): PointerTime = when (phase) {
        Phase.PRESSED -> press.at + tune.petDelay
        Phase.LETTING_GO -> press.releaseAt
        else -> PointerTime.NEVER
    }

    /** A press that may still turn out a tap or petting. */
    private fun undecided(): Boolean = phase == Phase.PRESSED || phase == Phase.TIMED

    /** Past the slop faster than any stroke. */
    private fun hurried(time: PointerTime): Boolean = beyondSlop() && press.speed(time) > tune.petMaxSpeed

    private fun beyondSlop(): Boolean = press.origin.distanceTo(press.last) > tune.slopDp

    private fun heldLongEnough(time: PointerTime): Boolean = time - press.at >= tune.petDelay

    private fun slowEnoughToPet(time: PointerTime): Boolean = press.speed(time) <= tune.petMaxSpeed

    /** A clean, quick press: a tap. */
    private fun isTap(time: PointerTime): Boolean = !heldLongEnough(time) && !beyondSlop()

    private fun longPressMoment(time: PointerTime): MascotGesture {
        if (!heldLongEnough(time)) return MascotGesture.NONE
        phase = Phase.TIMED
        return petIfHeldLongEnough(time)
    }

    private fun petIfHeldLongEnough(time: PointerTime): MascotGesture {
        if (!heldLongEnough(time) || !slowEnoughToPet(time)) return MascotGesture.NONE
        phase = Phase.PETTING
        return MascotGesture.PET_START
    }

    private fun release(time: PointerTime): MascotGesture {
        val tapped = isTap(time)
        phase = Phase.IDLE
        return if (tapped) taps.tap(time, press.origin) else MascotGesture.NONE
    }

    private fun letGo(time: PointerTime): MascotGesture {
        press.releaseAt = time + tune.petRelease
        phase = Phase.LETTING_GO
        return MascotGesture.NONE
    }

    private fun endPettingAt(time: PointerTime): MascotGesture {
        if (time < press.releaseAt) return MascotGesture.NONE
        phase = Phase.IDLE
        press.releaseAt = PointerTime.NEVER
        return MascotGesture.PET_END
    }

    private fun idle(): MascotGesture {
        phase = Phase.IDLE
        return MascotGesture.NONE
    }


    /** Pairs taps into double taps and spaces tricks out. */
    private class TapPairing(private val tune: MascotGestureTune) {
        private var lastTapAt = PointerTime.LONG_AGO
        private var lastTapPoint = GesturePoint(0f, 0f)
        private var lastTrickAt = PointerTime.LONG_AGO
        private var nextTrick = 0

        /** The trick the last [MascotGesture.TRICK] picked. */
        var trick = 0
            private set

        fun tap(time: PointerTime, point: GesturePoint): MascotGesture {
            if (!pairsWithLastTap(time, point)) {
                lastTapAt = time
                lastTapPoint = point
                return MascotGesture.TAP
            }
            // A pair is spent: a third tap starts a new pair instead of pairing with the second.
            lastTapAt = PointerTime.LONG_AGO
            if (trickCoolingDown(time)) return MascotGesture.NUDGE
            lastTrickAt = time
            trick = nextTrick
            nextTrick = (nextTrick + 1) % tune.trickCount.coerceAtLeast(1)
            return MascotGesture.TRICK
        }

        private fun pairsWithLastTap(time: PointerTime, point: GesturePoint): Boolean =
            time - lastTapAt <= tune.doubleTap && lastTapPoint.distanceTo(point) <= tune.doubleTapSlopDp

        private fun trickCoolingDown(time: PointerTime): Boolean = time - lastTrickAt < tune.trickCooldown
    }
}
