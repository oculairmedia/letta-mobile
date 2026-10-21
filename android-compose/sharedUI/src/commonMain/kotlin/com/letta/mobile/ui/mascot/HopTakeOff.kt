package com.letta.mobile.ui.mascot

import androidx.compose.ui.geometry.Rect
import com.letta.mobile.avatar.core.GazeMath
import com.letta.mobile.avatar.core.GazePoint
import com.letta.mobile.avatar.core.MascotDirector

/*
 * letta-mobile-te3n1.2 / te3n1.11: a seat hand-off goes at once. When the transport starts a hop
 * the director turns the eyes toward the next seat ([MascotDirector.leadGaze]) and the body takes
 * off on the same frame - nothing waits on the look - so switching agent or conversation moves the
 * character as the switch is committed. The director (and its gaze) is the slot's, so nothing
 * about the look resets on arrival.
 */

/** A hop that has started: the body leaves [from] for [destination]. */
internal class HopTakeOff(val from: Rect, val destination: Rect)

/** Where the eyes go for a hop: the destination seat's centre, in the gaze units of the mascot standing at [from]. */
internal fun seatLeadLook(hop: HopTakeOff, density: Float): GazePoint =
    GazeMath.pointerToGaze(hop.destination.center.x, hop.destination.center.y, hop.from.toGazeRect(), GAZE_MIN_REACH.value * density)

/** The eyes turn toward [hop]'s seat and the body is woken for the hop, on the same frame. */
internal fun MascotTransport.Flight.takeOff(hop: HopTakeOff, director: MascotDirector?, density: Float) {
    director?.leadGaze(seatLeadLook(hop, density))
    wakes++
}

/** A body left set moving (a hop that was turned into a snap) is stepped to rest rather than left crouched. */
internal fun MascotTransport.Flight.wakeIfMoving() {
    if (!body.atRest) wakes++
}
