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
 * (`WindowsWinUIConfig.calculateMouseWheelScroll`, in
 * `androidx.compose.foundation.gestures.DesktopScrollable.desktop.kt`), with
 * the sign inverted because a positive rotation moves content up. Inverting
 * that formula is the only way to make a synthetic wheel event travel the
 * same distance the finger did — *if* `viewportExtentPx` here matches the
 * `bounds: IntSize` Compose divides by there.
 *
 * It never does. Compose passes the scrollable modifier node's own layout
 * size as `bounds` (`MouseWheelScrollingLogic.onPointerEvent`) — the
 * LazyColumn's own height, not the window's. But AWT delivers every touch
 * event against a single Skia canvas per window (see this file's header for
 * why Compose has no per-composable touch path at all), so
 * [DesktopWindowsTouchInput] can only ever pass that canvas's — i.e. the
 * whole window content area's — width/height as `viewportExtentPx`. Every
 * touch surface in this app (chat transcript, agent sidebar, settings) sits
 * inside chrome the window includes but the scrollable excludes — at minimum
 * the 48dp title bar (`TitleBarHeight` in `DesktopJewelWindow.kt`), plus a
 * composer/header/filter row stack for most of them — so the true
 * `bounds.height` used on the Compose side is always smaller than the
 * `viewportExtentPx` used here. Because the formula's `bounds` divides the
 * scroll amount, a smaller true bounds than the one assumed means every
 * touch drag scrolls *less* than the finger moved, which is exactly the
 * "content moves less than the finger, and it feels slow" symptom this
 * constant exists to correct.
 *
 * There is no reflective or Compose-provided way to read a Compose node's
 * layout bounds from inside the AWT event queue this shim runs in, so the
 * true per-surface ratio can't be measured here. [TOUCH_SCROLL_GAIN] is a
 * fixed, named, empirically-chosen correction instead of a wrong formula
 * that only looks exact.
 */
internal const val WHEEL_SCROLL_DIVISOR = 20.0

/**
 * Fixed correction for the title-bar-and-chrome shortfall documented above,
 * tuned against the chat transcript — the surface the "flick and drag feel
 * slow" report was about, and the one with the smallest shortfall of the
 * three touch surfaces in this app. On a representative ~800dp-tall window,
 * the transcript's own height (window height minus the 48dp title bar minus
 * a composer/header of roughly similar order) comes out around 650-700dp
 * against the window's 800dp — a viewport extent about 1.15-1.25x too large,
 * which is what starves the drag of that same fraction of distance. 1.25
 * fully corrects that shortfall for the transcript without overshooting it.
 *
 * The agent sidebar and settings lists sit behind more excluded chrome
 * (nav rows, filter rows, section headers) than the transcript does, so
 * their true shortfall is larger than 1.25 and this constant leaves them
 * proportionally undercorrected. That is strictly an improvement over the
 * pre-fix 1.0 (no correction at all) on every touch surface in the app, not
 * a regression on any of them — it just isn't exact for surfaces smaller
 * than the transcript, the same way [WHEEL_SCROLL_DIVISOR] alone wasn't
 * exact for any of them. Retune this single constant if hardware testing
 * still finds the transcript short, or if the sidebar/settings shortfall
 * turns out to matter enough to warrant its own fix.
 */
internal const val TOUCH_SCROLL_GAIN = 1.25

internal fun wheelRotationForDrag(dragPx: Float, viewportExtentPx: Int): Double {
    if (viewportExtentPx <= 0) return 0.0
    return -dragPx.toDouble() * TOUCH_SCROLL_GAIN * WHEEL_SCROLL_DIVISOR / viewportExtentPx
}

/**
 * Smoothed per-axis velocity of a Windows touch pan, used to seed the coast
 * when the finger lifts.
 *
 * Axes are tracked separately on purpose. A single pan interleaves both: a
 * mostly-vertical swipe still emits small horizontal samples, frequently of
 * the opposite sign, between the vertical ones. Folding them into one running
 * average lets the minor axis pull the major axis' velocity toward — and past
 * — zero, which shows up on hardware as a fling that kicks backwards on
 * release.
 */
internal class DesktopTouchPanVelocity(
    private val maxPxPerMs: Float = MAX_PAN_VELOCITY_PX_PER_MS,
    private val smoothing: Float = PAN_VELOCITY_SMOOTHING,
) {
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastMillisX = 0L
    private var lastMillisY = 0L

    fun reset(atMillis: Long) {
        velocityX = 0f
        velocityY = 0f
        lastMillisX = atMillis
        lastMillisY = atMillis
    }

    fun record(sample: TouchPanSample) {
        val last = if (sample.horizontal) lastMillisX else lastMillisY
        val elapsed = (sample.atMillis - last).coerceAtLeast(1L)
        val instant = (sample.pixels / elapsed).coerceIn(-maxPxPerMs, maxPxPerMs)
        if (sample.horizontal) {
            lastMillisX = sample.atMillis
            velocityX = velocityX * (1f - smoothing) + instant * smoothing
        } else {
            lastMillisY = sample.atMillis
            velocityY = velocityY * (1f - smoothing) + instant * smoothing
        }
    }

    /**
     * The velocity to coast on: the dominant axis only, so a near-vertical
     * swipe does not drift sideways on release because of pan jitter.
     */
    fun dominant(): TouchScrollDelta =
        if (abs(velocityX) > abs(velocityY)) {
            TouchScrollDelta(dx = velocityX, dy = 0f)
        } else {
            TouchScrollDelta(dx = 0f, dy = velocityY)
        }
}

/**
 * One sample of an in-flight Windows touch pan: how far the content should
 * travel, when the sample landed, and which axis it belongs to.
 */
internal data class TouchPanSample(
    val pixels: Float,
    val atMillis: Long,
    val horizontal: Boolean,
)

/** Beyond this a "swipe" is sensor noise, not a gesture worth coasting on. */
internal const val MAX_PAN_VELOCITY_PX_PER_MS = 6f

/** Weight of the newest sample in the velocity average; the rest is history. */
internal const val PAN_VELOCITY_SMOOTHING = 0.35f
