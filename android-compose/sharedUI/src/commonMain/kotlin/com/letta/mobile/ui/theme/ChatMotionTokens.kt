package com.letta.mobile.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * letta-mobile-cc25e: motion of the shared chat page.
 *
 * The send flight lifts the sent text out of the prompt box and coasts it into the new prompt
 * row: the row makes room first ([SendFlight.INSERT_MILLIS]), the text travels on an eased
 * path that ramps up and settles without overshoot ([SendFlight.flightEasing]), then hands off
 * to the real row with a short cross-fade.
 */
object ChatMotionTokens {
    object SendFlight {
        /** Source to target. Long enough to read as a throw, short enough not to feel slow. */
        const val FLIGHT_MILLIS: Int = 420

        /** The timeline opens the new row's slot; done well before the text arrives. */
        const val INSERT_MILLIS: Int = 240

        /** Ghost fades out while the real row fades in. */
        const val HANDOFF_MILLIS: Int = LettaMotionTokens.FAST_FADE_IN_MILLIS

        /** How long the ghost waits at the prompt for its row to lay out before giving up. */
        const val TARGET_WAIT_MILLIS: Int = 700

        /** A ghost whose row never came (a queued send, a failed send) fades in place. */
        const val ABANDON_FADE_MILLIS: Int = LettaMotionTokens.EXIT_MILLIS

        /** Ramp up, ramp down, settle: the standard emphasized curve, which never overshoots. */
        val flightEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

        val insertEasing: Easing = FastOutSlowInEasing

        /** The bubble's own fill grows in over the first part of the trip. */
        const val FILL_IN_FRACTION: Float = 0.6f
    }

    /**
     * letta-mobile-bglj6.1: the docked panel growing into the full-screen page and shrinking
     * back. One eased progress drives the panel's rect, corner radius, shadow and the page
     * background; the two layouts cross-fade inside it.
     */
    object SurfaceMorph {
        /** Docked to full screen (and back). A reversal mid-way takes the remaining share. */
        const val MILLIS: Int = 340

        /** Ramps up and settles without overshoot. */
        val easing: Easing = FastOutSlowInEasing

        /**
         * Each layout fades over this share of the trip: the panel's own content is gone by
         * this point, the page's content starts this far from the end, so they overlap mid-way.
         */
        const val CROSSFADE_FRACTION: Float = 0.6f
    }
}
