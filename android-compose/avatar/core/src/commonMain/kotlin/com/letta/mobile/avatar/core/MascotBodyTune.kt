package com.letta.mobile.avatar.core

/**
 * The tune of a [MascotBody] (letta-mobile-te3n1.3). Stiffness is in 1/s^2 at unit mass, the same
 * unit as Compose's `spring(stiffness = ...)`, so a Compose spring and the body move alike.
 */
class MascotBodyTune {
    /** The hop between seats: one visible overshoot, settled within 1 % in about a third of a second. */
    val travelStiffness: Float = 380f
    val travelDamping: Float = 0.62f

    /** Squash/stretch and lean: quicker and bouncier than the travel, so the landing jiggles. */
    val deformStiffness: Float = 700f
    val deformDamping: Float = 0.45f

    /** Most the body stretches along its motion (16 %); the squashed axis then gives 1 - 1/1.16 (13.8 %). */
    val maxStretch: Float = 0.16f

    /** Most the body leans into horizontal motion, in degrees. */
    val maxLeanDegrees: Float = 8f

    /** The crouch before take-off, in seconds, and how deep it goes (fraction of the full squash). */
    val anticipationSeconds: Float = 0.07f
    val anticipationDepth: Float = 0.6f

    /** Travel speed, in dp/s, at which the stretch and lean are full. */
    val stretchSpeedDp: Float = 2400f

    /** Arrival speed, in dp/s, that lands with a full impact (1). */
    val impactSpeedDp: Float = 1200f

    /** Squash velocity (log-scale per second) a full impact (1) gives the body. */
    val impulseVelocity: Float = 7f
}

/** A velocity or a displacement in dp, for [MascotBody.hop] and [MascotBody.drag]. */
data class MascotVector(val xDp: Float, val yDp: Float)
