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

    /** Stops a coasting board where it is. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** A drag of the board begins at [position] (screen px) at [timeMillis]. */
    fun begin(timeMillis: Long, position: Offset) {
        stop()
        tracker.resetTracking()
        tracker.addPosition(timeMillis, position)
    }

    /** The finger dragging the board is at [position] at [timeMillis]. */
    fun track(timeMillis: Long, position: Offset) {
        tracker.addPosition(timeMillis, position)
    }

    /** The finger lifted: the board coasts on when it was moving fast enough to mean it. */
    fun release() {
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
        /** Below this (px/s) a lift is a stop, not a throw. */
        const val MIN_SPEED = 400f
        /** A wild flick still lands somewhere near. */
        const val MAX_SPEED = 8000f
        /** Higher stops sooner; tuned to feel like a list's fling. */
        const val FRICTION = 1.6f
    }
}
