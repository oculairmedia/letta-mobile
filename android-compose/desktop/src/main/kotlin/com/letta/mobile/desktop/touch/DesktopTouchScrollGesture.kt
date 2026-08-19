package com.letta.mobile.desktop.touch

import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.exp

/**
 * Platform-neutral maths and state machine behind Windows touch drag-to-scroll.
 *
 * Why this exists at all: AWT on Windows calls `RegisterTouchWindow` and
 * translates every `WM_TOUCH` message into an ordinary
 * [java.awt.event.MouseEvent], flagged only through the internal
 * `AWTAccessor.getMouseEventAccessor().isCausedByTouchEvent(...)` hook. Compose
 * Desktop's scene mediator has no touch path — it stamps every AWT pointer with
 * `PointerType.Mouse` — and Compose Foundation deliberately refuses drag
 * scrolling for mouse pointers (`CanDragCalculation` returns false for
 * `PointerType.Mouse`). Because AWT registered the window for touch, Windows'
 * own legacy gesture panning is disabled too, so nothing scrolls at all.
 *
 * [DesktopWindowsTouchInput] therefore swallows touch-caused mouse events and
 * replays them as synthetic wheel events. Everything in this file is the part
 * of that translation that does not need AWT, so it can be unit tested without
 * a touchscreen.
 */

/** A single finger position, in AWT component coordinates. */
internal data class TouchSample(val x: Int, val y: Int, val timeMillis: Long)

/** Scroll distance in device pixels, in screen-space (finger) direction. */
internal data class TouchScrollDelta(val dx: Float, val dy: Float)

/**
 * Latches a gesture's touch-vs-mouse origin at `MOUSE_PRESSED` and holds that
 * classification through the rest of the gesture, instead of trusting the
 * accessor's flag on every individual event.
 *
 * Measured on real Windows touch hardware (see `DesktopWindowsTouchInput`'s
 * class doc): `AWTAccessor`'s `isCausedByTouchEvent` is only ever true on
 * `MOUSE_PRESSED` and `MOUSE_RELEASED`. It is unconditionally false on
 * `MOUSE_DRAGGED` and the trailing `MOUSE_CLICKED` — for touch input just as
 * much as for a real mouse — so those events carry no usable signal of their
 * own. The gesture's origin can only be decided once, at press, and carried
 * forward; reclassifying each event independently is what made drag-to-scroll
 * and the touch keyboard dead on real hardware even though they worked
 * against every hand-fed test event.
 *
 * Not thread safe: driven entirely from the AWT event dispatch thread, same
 * as [DesktopTouchDragGesture].
 */
internal class DesktopTouchGestureLatch {
    private var latched: Boolean? = null

    /**
     * Returns the effective touch classification for [eventId], given what the
     * accessor reported for this specific event ([accessorSaysTouch]).
     *
     * `MOUSE_PRESSED` starts a new gesture and always re-latches from the
     * accessor's (trustworthy, for this event id) verdict — so a press can
     * never inherit a stale latch from whatever gesture came before it.
     * `MOUSE_DRAGGED` and `MOUSE_RELEASED` inherit the latch. `MOUSE_CLICKED`
     * also inherits the latch and then clears it, since it is the last event
     * AWT synthesizes for a press/release pair. Any other event id (enter,
     * exit, move, wheel — none of which participate in gesture tracking)
     * passes the accessor's verdict through unchanged and leaves the latch
     * alone.
     *
     * A `MOUSE_RELEASED` or `MOUSE_CLICKED` that arrives with no matching
     * latched press (the shim attached mid-gesture) falls back to the
     * accessor's verdict for that event rather than throwing or guessing.
     */
    fun classify(eventId: Int, accessorSaysTouch: Boolean): Boolean = when (eventId) {
        MouseEvent.MOUSE_PRESSED -> accessorSaysTouch.also { latched = it }
        MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED -> latched ?: accessorSaysTouch
        MouseEvent.MOUSE_CLICKED -> (latched ?: accessorSaysTouch).also { latched = null }
        else -> accessorSaysTouch
    }
}

/**
 * Latches whether a gesture's press landed inside a
 * [DesktopTouchDragExclusionRegistry] region (e.g. Nucleus's title bar) and
 * holds that verdict for the rest of the gesture — the same reasoning as
 * [DesktopTouchGestureLatch]: a finger that presses inside the excluded
 * region and then drags outside it must stay excluded rather than suddenly
 * starting to scroll mid-drag, so only `MOUSE_PRESSED` ever consults
 * [excludedAtPress].
 *
 * The latch clears itself after `MOUSE_CLICKED` — the last event a tap
 * inside the excluded region produces (release, then a synthesized click) —
 * so a stale verdict can never leak past a completed tap. A plain drag that
 * ends at release with no trailing click leaves the latch set; that is
 * harmless, since the next gesture's own `MOUSE_PRESSED` always re-evaluates
 * [excludedAtPress] and overwrites it unconditionally rather than trusting
 * whatever the latch already holds. A release or click that arrives with no
 * matching press — the shim attached mid-gesture — falls back to the latch's
 * initial `false`, degrading to "not excluded" rather than guessing, the same
 * fallback [DesktopTouchGestureLatch] uses.
 *
 * Not thread safe: driven entirely from the AWT event dispatch thread, same
 * as [DesktopTouchGestureLatch] and [DesktopTouchDragGesture].
 */
internal class DesktopTouchDragExclusionLatch {
    private var latched = false

    /**
     * Returns whether the current gesture is excluded. [excludedAtPress] is
     * evaluated only when [eventId] is `MOUSE_PRESSED`.
     */
    fun classify(eventId: Int, excludedAtPress: () -> Boolean): Boolean {
        if (eventId == MouseEvent.MOUSE_PRESSED) {
            latched = excludedAtPress()
        }
        val result = latched
        if (eventId == MouseEvent.MOUSE_CLICKED) {
            latched = false
        }
        return result
    }
}

/**
 * Which axis a gesture committed to. Locking on the first movement past the
 * slop keeps a vertical list from stealing a horizontal swipe (and vice versa)
 * the way an un-axis-locked wheel translation would.
 */
internal enum class TouchScrollAxis { Vertical, Horizontal }

/** How a touch gesture finished. */
internal sealed interface TouchGestureEnd {
    /**
     * The finger never travelled past the slop, so this was a tap: the caller
     * must replay the press it withheld, followed by the release.
     */
    data object Tap : TouchGestureEnd

    /**
     * The gesture scrolled. [velocityX]/[velocityY] are the finger velocity in
     * device pixels per millisecond at lift-off, already axis-locked and
     * clamped, for the caller to hand to [DesktopTouchFling].
     */
    data class Scrolled(
        val axis: TouchScrollAxis,
        val velocityX: Float,
        val velocityY: Float,
    ) : TouchGestureEnd
}

/**
 * Windows' own touch slop is ~12px at 100% scale; matching it keeps a tap on a
 * button from being eaten as a one-pixel scroll.
 */
internal const val DEFAULT_TOUCH_SLOP_PX = 12

/** Velocity is averaged over the tail of the gesture, not its whole length. */
private const val VELOCITY_WINDOW_MILLIS = 100L

/** ~8 px/ms is already a full screen height in ~100ms; beyond that is noise. */
private const val MAX_FLING_VELOCITY_PX_PER_MS = 8f

/**
 * Tracks one finger from press to release and reports the scroll distance to
 * emit for each intermediate drag.
 *
 * Not thread safe: it is driven entirely from the AWT event dispatch thread.
 */
internal class DesktopTouchDragGesture(
    private val slopPx: Int = DEFAULT_TOUCH_SLOP_PX,
    private val velocityWindowMillis: Long = VELOCITY_WINDOW_MILLIS,
    private val maxVelocityPxPerMs: Float = MAX_FLING_VELOCITY_PX_PER_MS,
) {
    private val samples = ArrayDeque<TouchSample>()
    private var origin: TouchSample? = null
    private var previous: TouchSample? = null

    /** Null until the finger travels past the slop. */
    var axis: TouchScrollAxis? = null
        private set

    /** True once this gesture has committed to scrolling. */
    val isScrolling: Boolean get() = axis != null

    fun press(sample: TouchSample) {
        samples.clear()
        samples.addLast(sample)
        origin = sample
        previous = sample
        axis = null
    }

    /**
     * Feeds a drag position. Returns the distance to scroll, or null while the
     * gesture is still inside the slop and might yet turn out to be a tap.
     *
     * The slop distance is deliberately consumed rather than replayed: emitting
     * the whole press-to-here vector on the frame that crosses the threshold
     * makes the content visibly jump under the finger.
     */
    fun drag(sample: TouchSample): TouchScrollDelta? {
        val start = origin ?: return null
        val last = previous ?: return null
        record(sample)
        previous = sample

        val lockedAxis = axis ?: run {
            val totalDx = sample.x - start.x
            val totalDy = sample.y - start.y
            if (abs(totalDx) < slopPx && abs(totalDy) < slopPx) return null
            val decided =
                if (abs(totalDy) >= abs(totalDx)) TouchScrollAxis.Vertical else TouchScrollAxis.Horizontal
            axis = decided
            decided
        }

        return when (lockedAxis) {
            TouchScrollAxis.Vertical -> TouchScrollDelta(dx = 0f, dy = (sample.y - last.y).toFloat())
            TouchScrollAxis.Horizontal -> TouchScrollDelta(dx = (sample.x - last.x).toFloat(), dy = 0f)
        }
    }

    fun release(sample: TouchSample): TouchGestureEnd {
        record(sample)
        val lockedAxis = axis ?: return TouchGestureEnd.Tap
        val velocity = velocity()
        return when (lockedAxis) {
            TouchScrollAxis.Vertical -> TouchGestureEnd.Scrolled(lockedAxis, 0f, velocity.dy)
            TouchScrollAxis.Horizontal -> TouchGestureEnd.Scrolled(lockedAxis, velocity.dx, 0f)
        }
    }

    private fun record(sample: TouchSample) {
        samples.addLast(sample)
        while (samples.size > 2 && sample.timeMillis - samples.first().timeMillis > velocityWindowMillis) {
            samples.removeFirst()
        }
    }

    private fun velocity(): TouchScrollDelta {
        val oldest = samples.firstOrNull() ?: return TouchScrollDelta(0f, 0f)
        val newest = samples.lastOrNull() ?: return TouchScrollDelta(0f, 0f)
        val elapsed = newest.timeMillis - oldest.timeMillis
        if (elapsed <= 0L) return TouchScrollDelta(0f, 0f)
        return TouchScrollDelta(
            dx = clampVelocity((newest.x - oldest.x).toFloat() / elapsed),
            dy = clampVelocity((newest.y - oldest.y).toFloat() / elapsed),
        )
    }

    private fun clampVelocity(value: Float): Float =
        value.coerceIn(-maxVelocityPxPerMs, maxVelocityPxPerMs)
}

/** Below this the fling has visually stopped; keeping the timer alive costs frames for nothing. */
private const val MIN_FLING_VELOCITY_PX_PER_MS = 0.05f

/** e-folds roughly every 250ms, which reads as a normal touch-scroll coast. */
private const val DEFAULT_FLING_DECAY_PER_MS = 0.004f

/**
 * Exponential-decay kinetic scrolling for the coast after the finger lifts.
 *
 * Compose Foundation's own fling lives behind the drag path we cannot reach
 * (see the file header), so the wheel translation has to supply its own
 * inertia or every scroll would stop dead at lift-off.
 */
internal class DesktopTouchFling(
    velocityX: Float,
    velocityY: Float,
    private val decayPerMs: Float = DEFAULT_FLING_DECAY_PER_MS,
    private val minVelocityPxPerMs: Float = MIN_FLING_VELOCITY_PX_PER_MS,
) {
    private var vx = velocityX
    private var vy = velocityY

    /** True once the fling has decayed below the point of visible motion. */
    val isFinished: Boolean
        get() = abs(vx) < minVelocityPxPerMs && abs(vy) < minVelocityPxPerMs

    /**
     * Advances by [deltaMillis] and returns the distance travelled, or null
     * once the fling is spent.
     */
    fun advance(deltaMillis: Long): TouchScrollDelta? {
        if (isFinished || deltaMillis <= 0L) return null
        val delta = TouchScrollDelta(dx = vx * deltaMillis, dy = vy * deltaMillis)
        val decay = exp(-decayPerMs * deltaMillis.toDouble()).toFloat()
        vx *= decay
        vy *= decay
        return delta
    }
}

/**
 * Compose Desktop's Windows scroll config resolves a wheel event to
 * `preciseWheelRotation * scrollAmount * (viewportExtent / 20)` pixels
 * (`WindowsWinUIConfig.calculateMouseWheelScroll`), with the sign inverted
 * because a positive rotation moves content up. Inverting that formula is the
 * only way to make a synthetic wheel event travel the same distance the finger
 * did.
 *
 * The viewport extent is unknowable at the AWT layer, so the Compose content
 * component's own size stands in for it. That is exact for the full-height
 * lists this fixes (chat transcript, agent sidebar, settings) and only
 * proportionally off for a small nested scroller.
 */
internal const val WHEEL_SCROLL_DIVISOR = 20.0

internal fun wheelRotationForDrag(dragPx: Float, viewportExtentPx: Int): Double {
    if (viewportExtentPx <= 0) return 0.0
    return -dragPx.toDouble() * WHEEL_SCROLL_DIVISOR / viewportExtentPx
}
