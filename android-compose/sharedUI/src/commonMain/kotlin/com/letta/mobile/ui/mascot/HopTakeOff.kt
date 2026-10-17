package com.letta.mobile.ui.mascot

import androidx.compose.ui.geometry.Rect
import com.letta.mobile.avatar.core.GazeMath
import com.letta.mobile.avatar.core.GazePoint
import com.letta.mobile.avatar.core.MascotDirector
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/*
 * letta-mobile-te3n1.2: a seat hand-off leads with the eyes. When the transport starts a hop the
 * body crouches where it stands, the director turns the eyes toward the next seat
 * ([MascotDirector.leadGaze]), and only after [MascotDirector.seatLeadSeconds] does the body take
 * off. The director (and its gaze) is the slot's, so nothing about the look resets on arrival.
 */

/** A hop that has started: the body is crouched at [from] and waits on the eyes before it goes to [destination]. */
internal class HopTakeOff(val from: Rect, val destination: Rect)

/** Where the eyes go for a hop: the destination seat's centre, in the gaze units of the mascot standing at [from]. */
internal fun seatLeadLook(hop: HopTakeOff, density: Float): GazePoint =
    GazeMath.pointerToGaze(hop.destination.center.x, hop.destination.center.y, hop.from.toGazeRect(), GAZE_MIN_REACH.value * density)

/** The eyes lead toward [hop]'s seat, then the body is woken for the hop; with no director it goes at once. */
internal suspend fun MascotTransport.Flight.takeOff(hop: HopTakeOff, director: MascotDirector?, density: Float) {
    if (director != null) {
        director.leadGaze(seatLeadLook(hop, density))
        delay(director.seatLeadSeconds.toDouble().seconds)
    }
    wakes++
}

/** A body left set moving (a hop whose take-off was cancelled) is stepped to rest rather than left crouched. */
internal fun MascotTransport.Flight.wakeIfMoving() {
    if (!body.atRest) wakes++
}
