package com.letta.mobile.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring

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

    /**
     * letta-mobile-bglj6.1: the docked panel folding down onto its bar (the mascot over it), and
     * back. The panel's top edge slides while its surface and conversation cross-fade with the
     * mascot and its bubble; the bar itself never moves, and the mascot glides between its spots.
     */
    object DockCollapse {
        const val MILLIS: Int = 180
    }

    /**
     * letta-mobile-bglj6.1: the ambient thinking glow in the docked panel and around the
     * minimised dock. The same glides as the hosts' page glow (desktop
     * DesktopAmbientChatBackground, Android AmbientShaderAgentBackground), so a status change
     * eases the tint, breath rate and agitation instead of popping.
     */
    object AmbientGlow {
        /** Tint, speed and agitation glide between statuses over this long. */
        const val GLIDE_MILLIS: Int = 600

        /** A frame longer than this (a stall, a backgrounded window) advances the glow no further. */
        const val MAX_FRAME_DELTA_SECONDS: Float = 0.1f

        /** What one visible stream delta adds to the stream energy (0..1). */
        const val STREAM_IMPULSE: Float = 0.35f

        /** How fast the stream energy decays back to calm between deltas. */
        const val STREAM_ENERGY_DECAY_SECONDS: Float = 0.9f
    }

    /**
     * letta-mobile-bglj6.1: the scroll-to-latest glide. A tap far up the history snaps to about
     * [GLIDE_VIEWPORTS] from the newest edge, then one underdamped spring carries the list the
     * rest of the way: it eases into the edge, runs a little past it (a lift of the rows, never a
     * scroll past the end, so the platform's own overscroll is left alone) and settles back.
     */
    object ScrollToLatest {
        /** Under 1, so the glide runs past the edge once and settles: by about 5% at this ratio. */
        const val DAMPING_RATIO: Float = 0.7f

        /** The whole glide reads as one short gesture, under half a second. */
        const val STIFFNESS: Float = Spring.StiffnessMediumLow

        /** How far the glide itself travels, in viewports; anything further is snapped first. */
        const val GLIDE_VIEWPORTS: Float = 1f
    }
}
