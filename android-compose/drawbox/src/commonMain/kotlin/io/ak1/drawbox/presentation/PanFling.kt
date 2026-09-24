package io.ak1.drawbox.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Inertia for a board pan, the way a phone's lists coast: the finger's path is tracked while it
 * drags the board, and when it lifts the board carries on at the release velocity, slowing to a
 * stop. Any new touch stops it, so a coasting board can be caught.
 *
 * [scope] must run on a frame clock (a composition's coroutine scope); [onPan] receives each
 * frame's screen-pixel delta, as a drag's would be.
 */
class PanFling(private val scope: CoroutineScope, private val onPan: (Offset) -> Unit) {
    private val tracker = VelocityTracker()
    private var job: Job? = null
    private var idleWait: Job? = null
    private var lastSampleMillis = 0L

    /** Whether a [releaseWhenIdle] is waiting: the drag it belongs to is still going on. */
    val awaitingIdle: Boolean get() = idleWait?.isActive == true

    /** Stops a coasting board where it is, and any coast waiting to start. */
    fun stop() {
        job?.cancel()
        job = null
        idleWait?.cancel()
        idleWait = null
    }

    /** A drag of the board begins at [position] (screen px) at [timeMillis]. */
    fun begin(timeMillis: Long, position: Offset) {
        stop()
        tracker.resetTracking()
        tracker.addPosition(timeMillis, position)
        lastSampleMillis = timeMillis
    }

    /** The finger dragging the board is at [position] at [timeMillis]. */
    fun track(timeMillis: Long, position: Offset) {
        tracker.addPosition(timeMillis, position)
        lastSampleMillis = timeMillis
    }

    /**
     * The drag has no lift of its own (a scroll burst): it is over once no step has come for
     * [idleMillis], and then it coasts if [coast], else simply ends. Each call restarts the wait.
     */
    fun releaseWhenIdle(idleMillis: Long, coast: Boolean) {
        idleWait?.cancel()
        idleWait = scope.launch {
            kotlinx.coroutines.delay(idleMillis)
            idleWait = null
            if (coast) release() else tracker.resetTracking()
        }
    }

    /**
     * The finger lifted, at [liftMillis] when known: the board coasts on when it was moving fast
     * enough to mean it. A finger that stopped before it lifted threw nothing, however fast it
     * had been going: the velocity is measured at its last move, not at the lift.
     */
    fun release(liftMillis: Long? = null) {
        if (liftMillis != null && liftMillis - lastSampleMillis > STOPPED_MS) {
            tracker.resetTracking()
            return
        }
        val measured = tracker.calculateVelocity()
        tracker.resetTracking()
        var velocity = Offset(measured.x, measured.y)
        val speed = velocity.getDistance()
        // Samples at one instant (a pinch's fingers report together) measure no velocity at all:
        // a NaN that is not below the threshold, and would carry the camera off to nowhere.
        if (!speed.isFinite() || speed < MIN_SPEED) return
        if (speed > MAX_SPEED) velocity *= MAX_SPEED / speed
        job = scope.launch {
            var last = Offset.Zero
            Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(velocity, exponentialDecay(FRICTION)) {
                onPan(value - last)
                last = value
            }
        }
    }

    private companion object {
        /** A finger still this long before it lifts has stopped (as Android's own trackers assume). */
        const val STOPPED_MS = 40L
        /** Below this (px/s) a lift is a stop, not a throw. */
        const val MIN_SPEED = 400f
        /** A wild flick still lands somewhere near. */
        const val MAX_SPEED = 8000f
        /**
         * Higher stops sooner. 1.6 stopped a throw within a hand's width, which read as drag, not
         * glide; a board should coast like a thrown map, well past where the finger let go.
         */
        const val FRICTION = 0.6f
    }
}
