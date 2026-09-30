package com.letta.mobile.desktop.input

import com.letta.mobile.desktop.touch.DesktopSmoothScrollSwitch
import com.letta.mobile.desktop.touch.DesktopTouchFling
import com.letta.mobile.desktop.touch.DesktopTouchKeyboardTaps
import com.letta.mobile.desktop.touch.DesktopTouchOrigin
import com.letta.mobile.desktop.touch.DesktopTouchSmoothScrollSuppressor
import com.letta.mobile.desktop.touch.MAX_FLING_VELOCITY_PX_PER_MS
import com.letta.mobile.desktop.touch.VELOCITY_WINDOW_MILLIS
import com.letta.mobile.desktop.touch.wheelRotationForDrag
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * A finger contact from the pen bridge.
 *
 * Windows Ink reports a finger through the same channel as the stylus, and it
 * has no pressure axis. Replaying it as a stream of mouse moves is the arrow
 * cursor. Dropping it is no touch at all. A short contact is one click, which
 * is what focuses the prompt and raises the touch keyboard. A longer one
 * scrolls with the finger, and the lift coasts with the same fling the other
 * touch path uses. A tap does not scroll: the contact-down sample is the
 * previous pose, and the first move is where the finger actually is.
 */
internal class TabletFingerPointer(
    private val smoothScroll: DesktopTouchSmoothScrollSuppressor = DesktopTouchSmoothScrollSuppressor(
        DesktopSmoothScrollSwitch.bindOrNull(),
    ),
    private val clock: () -> Long = System::currentTimeMillis,
    private val flingFrameMillis: Int = FINGER_FLING_FRAME_MILLIS,
) {
    private val contact = FingerContact()
    private val velocity = FingerFlingVelocity()
    private var last = Point()
    private var lastMillis = 0L
    private var velocitySeeded = false
    private var flingTimer: Timer? = null

    /** True while a lift is still coasting. */
    internal val isFlinging: Boolean get() = flingTimer?.isRunning == true

    fun onSample(target: Component, sample: TabletPenDecoder.DecodedSample) {
        val point = Point(sample.x.toInt(), sample.y.toInt())
        when (sample.kind) {
            TabletBridge.KIND_DOWN -> {
                stopFling()
                contact.down()
                velocity.reset()
                velocitySeeded = false
                DesktopTouchOrigin.record(isTouch = true, atMillis = clock())
            }
            TabletBridge.KIND_MOVE -> onMove(target, point)
            TabletBridge.KIND_UP, TabletBridge.KIND_OUT -> onLift(target)
        }
    }

    fun cancel() {
        contact.cancel()
        velocity.reset()
        velocitySeeded = false
        stopFling()
    }

    /** One click where the finger was, after the board decided the contact was a tap. */
    fun tapAt(target: Component, x: Int, y: Int) {
        click(target, Point(x, y))
    }

    private fun onMove(target: Component, point: Point) {
        val now = clock()
        if (!contact.move(point.x, point.y)) {
            last = point
            lastMillis = now
            return
        }
        if (!velocitySeeded) {
            velocity.record(last.y, lastMillis)
            velocitySeeded = true
        }
        velocity.record(point.y, now)
        smoothScroll.begin()
        scrollBy(target, point, (point.y - last.y).toFloat())
        last = point
        lastMillis = now
    }

    private fun onLift(target: Component) {
        val lift = contact.up()
        velocitySeeded = false
        when {
            lift == null -> smoothScroll.end()
            lift.tap -> {
                velocity.reset()
                smoothScroll.end()
                click(target, Point(lift.x, lift.y))
                SwingUtilities.invokeLater { DesktopTouchKeyboardTaps.gate?.fingerTapped() }
            }
            else -> {
                val speed = velocity.liftVelocityY(clock())
                velocity.reset()
                startFling(target, last, speed)
            }
        }
    }

    private fun startFling(target: Component, at: Point, velocityY: Float) {
        val fling = DesktopTouchFling(velocityX = 0f, velocityY = velocityY)
        if (fling.isFinished) {
            smoothScroll.end()
            return
        }
        var lastFrameMillis = System.currentTimeMillis()
        val timer = Timer(flingFrameMillis, null)
        timer.addActionListener {
            if (flingTimer !== timer) {
                timer.stop()
                return@addActionListener
            }
            val now = System.currentTimeMillis()
            val delta = fling.advance(now - lastFrameMillis)
            lastFrameMillis = now
            if (delta == null || !target.isShowing) {
                timer.stop()
                if (flingTimer === timer) flingTimer = null
                exitPointer(target, at)
                smoothScroll.end()
            } else {
                scrollBy(target, at, delta.dy)
                // A wheel event parks Compose's pointer on the lift point. Without
                // an exit, every row that coasts past that point highlights.
                exitPointer(target, at)
            }
        }
        flingTimer = timer
        timer.start()
    }

    private fun stopFling() {
        flingTimer?.stop()
        flingTimer = null
        smoothScroll.end()
    }

    private fun scrollBy(target: Component, at: Point, dyPx: Float) {
        val rotation = wheelRotationForDrag(dyPx, target.height)
        if (rotation == 0.0) return
        target.dispatchEvent(
            MouseWheelEvent(
                target,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                at.x,
                at.y,
                at.x,
                at.y,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                rotation.roundToInt(),
                rotation,
            ),
        )
    }

    private fun click(target: Component, point: Point) {
        DesktopTouchOrigin.record(isTouch = true, atMillis = clock())
        val time = clock()
        fun post(id: Int, modifiers: Int, whenMillis: Long) {
            target.dispatchEvent(
                MouseEvent(
                    target,
                    id,
                    whenMillis,
                    modifiers,
                    point.x,
                    point.y,
                    if (id == MouseEvent.MOUSE_CLICKED) 1 else 0,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }
        post(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK, time)
        post(MouseEvent.MOUSE_RELEASED, 0, time + 1)
        post(MouseEvent.MOUSE_CLICKED, 0, time + 2)
        exitPointer(target, point)
    }

    /** Tells Compose the pointer left, so a parked cursor does not highlight what scrolls past it. */
    fun exitPointer(target: Component, at: Point) {
        target.dispatchEvent(
            MouseEvent(
                target,
                MouseEvent.MOUSE_EXITED,
                clock(),
                0,
                at.x,
                at.y,
                0,
                false,
                MouseEvent.NOBUTTON,
            ),
        )
    }

    private companion object {
        const val FINGER_FLING_FRAME_MILLIS = 16
    }
}

/** Where the finger was when it lifted, and whether that contact was a tap. */
internal data class FingerLift(val tap: Boolean, val x: Int, val y: Int)

/**
 * Tap versus scroll for one finger.
 *
 * The contact-down sample is the bridge's previous pose, often far from the
 * finger. The first move is the real location. Measuring the slop from the
 * down sample turns every tap into a scroll that jumps the page to the finger.
 */
internal class FingerContact(
    private val slopPx: Int = FINGER_TAP_SLOP_PX,
) {
    private var active = false
    private var placed = false
    private var scrolling = false
    private var anchorX = 0
    private var anchorY = 0

    fun down() {
        active = true
        placed = false
        scrolling = false
    }

    /**
     * False while the finger is still being placed or is inside the tap slop.
     * True only for movement that should scroll, measured from the placed finger.
     */
    fun move(x: Int, y: Int): Boolean {
        if (!active) return false
        if (!placed) {
            placed = true
            anchorX = x
            anchorY = y
            return false
        }
        if (!scrolling) {
            val dx = x - anchorX
            val dy = y - anchorY
            if (dx * dx + dy * dy <= slopPx * slopPx) return false
            scrolling = true
        }
        return true
    }

    /** The placed finger, or null when the contact never reported a real position. */
    fun up(): FingerLift? {
        if (!active) return null
        val lift = if (placed) FingerLift(tap = !scrolling, x = anchorX, y = anchorY) else null
        active = false
        placed = false
        scrolling = false
        return lift
    }

    fun cancel() {
        active = false
        placed = false
        scrolling = false
    }

    companion object {
        const val FINGER_TAP_SLOP_PX = 12
    }
}

/**
 * Vertical speed of a finger drag, averaged over the last [windowMillis].
 *
 * Only scrolling moves are recorded. The placement sample and the stale
 * contact-down sample are not, so a tap cannot seed a fling. A lift that
 * arrives after the finger has already stopped returns zero.
 */
internal class FingerFlingVelocity(
    private val windowMillis: Long = VELOCITY_WINDOW_MILLIS,
    private val maxPxPerMs: Float = MAX_FLING_VELOCITY_PX_PER_MS,
    private val stoppedMillis: Long = FINGER_STOPPED_MILLIS,
) {
    private val samples = ArrayDeque<Sample>()

    fun reset() {
        samples.clear()
    }

    fun record(y: Int, atMillis: Long) {
        samples.addLast(Sample(y, atMillis))
        while (samples.size > 2 && atMillis - samples.first().atMillis > windowMillis) {
            samples.removeFirst()
        }
    }

    fun liftVelocityY(atMillis: Long): Float {
        val newest = samples.lastOrNull() ?: return 0f
        if (atMillis - newest.atMillis > stoppedMillis) return 0f
        if (samples.size < 2) return 0f
        val oldest = samples.first()
        val elapsed = newest.atMillis - oldest.atMillis
        if (elapsed <= 0L) return 0f
        return ((newest.y - oldest.y).toFloat() / elapsed).coerceIn(-maxPxPerMs, maxPxPerMs)
    }

    private data class Sample(val y: Int, val atMillis: Long)

    companion object {
        const val FINGER_STOPPED_MILLIS = 40L
    }
}
