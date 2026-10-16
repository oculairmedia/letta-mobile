package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.time.Duration

/**
 * The mascot's body as a few springs (letta-mobile-te3n1.3): where it is along a hop between two
 * seats, and how it squashes, stretches and leans while it travels and lands. Pure and
 * deterministic: the same steps give the same pose on Android, desktop and web, and a step
 * allocates nothing, so it can run every frame.
 *
 * The body knows nothing of seats or pixels. A hop is a vector in dp; [travel] runs 0..1 along it
 * (past 1 while it overshoots). The deformation is one log-scale `d`: [scaleY] = e^d and
 * [scaleX] = e^-d, so the body's area never changes; positive `d` stretches it upright, negative
 * squashes it flat. Scale and lean pivot at the feet.
 */
class MascotBody(val tune: MascotBodyTune = MascotBodyTune()) {
    /** Progress along the current hop: 0 at take-off, 1 at the seat; overshoots past 1 once. */
    var travel: Float = 1f
        private set

    /** [travel] per second. */
    var travelVelocity: Float = 0f
        private set

    private var hopX = 0f
    private var hopY = 0f
    private var crouchLeft = 0f
    private var landed = true
    private var deform = 0f
    private var deformVelocity = 0f
    private var lean = 0f
    private var leanVelocity = 0f
    private var dragX = 0f
    private var dragY = 0f

    /** The sub-step being integrated, in seconds. */
    private var h = 0f

    /** Where [travel] was before the current sub-step. */
    private var travelBefore = 1f

    /** Width scale about the feet; [scaleX] * [scaleY] is always 1. */
    val scaleX: Float get() = exp(-deform)

    /** Height scale about the feet. */
    val scaleY: Float get() = exp(deform)

    /** Lean in degrees, clockwise, into horizontal motion. */
    val rotationDegrees: Float get() = lean

    /**
     * Horizontal motion - seat travel plus a drag - as -1..1 of [MascotBodyTune.stretchSpeedDp]:
     * host data for the rig's moveX (the host reads the pointer; the file only animates).
     */
    val motionX: Float get() = (velocityX() / tune.stretchSpeedDp).coerceIn(-1f, 1f)

    /** Vertical motion, -1..1 (down positive), for the rig's moveY. */
    val motionY: Float get() = (velocityY() / tune.stretchSpeedDp).coerceIn(-1f, 1f)

    /** Nothing moves: the body stands at its seat, undeformed, and stepping changes nothing. */
    val atRest: Boolean
        get() = landed && crouchLeft <= 0f && dragX == 0f && dragY == 0f &&
            abs(1f - travel) < REST_TRAVEL && abs(travelVelocity) < REST_TRAVEL_SPEED &&
            abs(deform) < REST_DEFORM && abs(deformVelocity) < REST_DEFORM_SPEED &&
            abs(lean) < REST_LEAN && abs(leanVelocity) < REST_LEAN_SPEED

    private val maxDeform = ln(1f + tune.maxStretch)
    private val travelOmega = sqrt(tune.travelStiffness)
    private val deformOmega = sqrt(tune.deformStiffness)

    /**
     * Starts a hop of [offset] from where the body is drawn now. A body standing still crouches
     * first ([MascotBodyTune.anticipationSeconds]); one already in flight carries on into the new
     * hop without a crouch, keeping its squash and lean.
     */
    fun hop(offset: MascotVector) {
        val wasStill = atRest
        hopX = offset.xDp
        hopY = offset.yDp
        travel = 0f
        travelVelocity = 0f
        landed = false
        crouchLeft = if (wasStill) tune.anticipationSeconds else 0f
    }

    /**
     * A squash of [impact] (0..1) from outside: a poke, a drop from a drag (M4), or a landing the
     * caller decides on. A landing at the end of a hop is applied by [step] itself.
     */
    fun impulse(impact: Float) {
        deformVelocity -= impact.coerceIn(0f, 1f) * tune.impulseVelocity
    }

    /**
     * The pointer's [velocity] (dp/s) while the user drags the character, null when the drag
     * ends: the body stretches and leans along it like seat travel, and [motionX]/[motionY]
     * report it to the rig. The host measures it; the body never reads input itself.
     */
    fun drag(velocity: MascotVector?) {
        dragX = velocity?.xDp ?: 0f
        dragY = velocity?.yDp ?: 0f
    }

    /** Stands the body at its seat, undeformed, at once: reduced motion, or a seat that appears in place. */
    fun snap() {
        dragX = 0f
        dragY = 0f
        travel = 1f
        travelVelocity = 0f
        crouchLeft = 0f
        landed = true
        deform = 0f
        deformVelocity = 0f
        lean = 0f
        leanVelocity = 0f
    }

    /**
     * Advances the body by [delta] and returns the landing impact (0..1) when the hop arrives at
     * its seat during this step, else 0. Runs in fixed sub-steps, so a long frame cannot blow the
     * springs up and the result depends only on the steps given.
     */
    fun step(delta: Duration): Float {
        val seconds = delta.inWholeNanoseconds / NANOS_PER_SECOND
        if (seconds <= 0f) return 0f
        val count = ceil(seconds / MAX_SUBSTEP).toInt().coerceAtLeast(1)
        h = seconds / count
        var impact = 0f
        repeat(count) {
            val landing = substep()
            if (landing > 0f) impact = landing
        }
        if (atRest) snap()
        return impact
    }

    private fun substep(): Float {
        var impact = 0f
        if (crouchLeft > 0f) {
            crouchLeft -= h
        } else if (travelling()) {
            travelBefore = travel
            travelVelocity += ((1f - travel) * tune.travelStiffness - 2f * tune.travelDamping * travelOmega * travelVelocity) * h
            travel += travelVelocity * h
            if (arrived()) {
                landed = true
                impact = (abs(speedDp()) / tune.impactSpeedDp).coerceIn(0f, 1f)
                impulse(impact)
            }
        }
        stepDeform()
        return impact
    }

    /** The travel spring has anything left to do: a hop not yet landed, or an overshoot settling. */
    private fun travelling(): Boolean {
        if (!landed) return true
        return travel != 1f || travelVelocity != 0f
    }

    /** This sub-step carried a hop that had not landed across its seat. */
    private fun arrived(): Boolean {
        if (landed) return false
        return travelBefore < 1f && travel >= 1f
    }

    /** The body's speed along the hop, in dp/s. */
    private fun speedDp(): Float = travelVelocity * sqrt(hopX * hopX + hopY * hopY)

    private fun velocityX(): Float = travelVelocity * hopX + dragX

    private fun velocityY(): Float = travelVelocity * hopY + dragY

    private fun stepDeform() {
        val deformTarget: Float
        val leanTarget: Float
        if (crouchLeft > 0f) {
            deformTarget = -maxDeform * tune.anticipationDepth
            leanTarget = 0f
        } else {
            val vx = velocityX()
            val vy = velocityY()
            val speed2 = vx * vx + vy * vy
            val speed = sqrt(speed2)
            val amount = (speed / tune.stretchSpeedDp).coerceIn(0f, 1f)
            // Upright travel stretches tall (d > 0); sideways travel stretches wide (d < 0).
            deformTarget = if (speed2 > 0f) maxDeform * amount * (vy * vy - vx * vx) / speed2 else 0f
            leanTarget = tune.maxLeanDegrees * (vx / tune.stretchSpeedDp).coerceIn(-1f, 1f)
        }
        val k = tune.deformStiffness
        val c = 2f * tune.deformDamping * deformOmega
        deformVelocity += ((deformTarget - deform) * k - c * deformVelocity) * h
        deform += deformVelocity * h
        if (deform > maxDeform || deform < -maxDeform) {
            deform = deform.coerceIn(-maxDeform, maxDeform)
            deformVelocity = 0f
        }
        leanVelocity += ((leanTarget - lean) * k - c * leanVelocity) * h
        lean = (lean + leanVelocity * h).coerceIn(-tune.maxLeanDegrees, tune.maxLeanDegrees)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SUBSTEP = 1f / 240f
        const val REST_TRAVEL = 0.001f
        const val REST_TRAVEL_SPEED = 0.01f
        const val REST_DEFORM = 0.0015f
        const val REST_DEFORM_SPEED = 0.08f
        const val REST_LEAN = 0.05f
        const val REST_LEAN_SPEED = 0.5f
    }
}
