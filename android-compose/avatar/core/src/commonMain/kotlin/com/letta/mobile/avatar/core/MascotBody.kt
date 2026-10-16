package com.letta.mobile.avatar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tune of a [MascotBody] (letta-mobile-te3n1.3). Stiffness is in 1/s^2 at unit mass, the same
 * unit as Compose's `spring(stiffness = ...)`, so a Compose spring and the body move alike.
 */
data class MascotBodyTune(
    /** The hop between seats: one visible overshoot, settled within 1 % in about a third of a second. */
    val travelStiffness: Float = 380f,
    val travelDamping: Float = 0.62f,
    /** Squash/stretch and lean: quicker and bouncier than the travel, so the landing jiggles. */
    val deformStiffness: Float = 700f,
    val deformDamping: Float = 0.45f,
    /** Most the body stretches along its motion (16 %); the squashed axis then gives 1 - 1/1.16 (13.8 %). */
    val maxStretch: Float = 0.16f,
    /** Most the body leans into horizontal motion, in degrees. */
    val maxLeanDegrees: Float = 8f,
    /** The crouch before take-off, in seconds, and how deep it goes (fraction of the full squash). */
    val anticipationSeconds: Float = 0.07f,
    val anticipationDepth: Float = 0.6f,
    /** Travel speed, in dp/s, at which the stretch and lean are full. */
    val stretchSpeedDp: Float = 2400f,
    /** Arrival speed, in dp/s, that lands with a full impact (1). */
    val impactSpeedDp: Float = 1200f,
    /** Squash velocity (log-scale per second) a full impact (1) gives the body. */
    val impulseVelocity: Float = 7f,
)

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
     * Starts a hop of ([dxDp], [dyDp]) from where the body is drawn now. A body standing still
     * crouches first ([MascotBodyTune.anticipationSeconds]); one already in flight carries on into
     * the new hop without a crouch, keeping its squash and lean.
     */
    fun hop(dxDp: Float, dyDp: Float) {
        val wasStill = atRest
        hopX = dxDp
        hopY = dyDp
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
     * The pointer's velocity, in dp/s, while the user drags the character (0, 0 when the drag
     * ends): the body stretches and leans along it like seat travel, and [motionX]/[motionY]
     * report it to the rig. The host measures it; the body never reads input itself.
     */
    fun drag(vxDp: Float, vyDp: Float) {
        dragX = vxDp
        dragY = vyDp
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
     * Advances the body by [deltaSeconds] and returns the landing impact (0..1) when the hop
     * arrives at its seat during this step, else 0. Runs in fixed sub-steps, so a long frame cannot
     * blow the springs up and the result depends only on the steps given.
     */
    fun step(deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f) return 0f
        val count = ceil(deltaSeconds / MAX_SUBSTEP).toInt().coerceAtLeast(1)
        val h = deltaSeconds / count
        var impact = 0f
        repeat(count) {
            val landing = substep(h)
            if (landing > 0f) impact = landing
        }
        if (atRest) snap()
        return impact
    }

    private fun substep(h: Float): Float {
        var impact = 0f
        if (crouchLeft > 0f) {
            crouchLeft -= h
        } else if (!landed || travel != 1f || travelVelocity != 0f) {
            val before = travel
            travelVelocity += ((1f - travel) * tune.travelStiffness - 2f * tune.travelDamping * travelOmega * travelVelocity) * h
            travel += travelVelocity * h
            if (!landed && before < 1f && travel >= 1f) {
                landed = true
                impact = (abs(speedDp()) / tune.impactSpeedDp).coerceIn(0f, 1f)
                impulse(impact)
            }
        }
        stepDeform(h)
        return impact
    }

    /** The body's speed along the hop, in dp/s. */
    private fun speedDp(): Float = travelVelocity * sqrt(hopX * hopX + hopY * hopY)

    private fun velocityX(): Float = travelVelocity * hopX + dragX

    private fun velocityY(): Float = travelVelocity * hopY + dragY

    private fun stepDeform(h: Float) {
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

    companion object {
        private const val MAX_SUBSTEP = 1f / 240f
        private const val REST_TRAVEL = 0.001f
        private const val REST_TRAVEL_SPEED = 0.01f
        private const val REST_DEFORM = 0.0015f
        private const val REST_DEFORM_SPEED = 0.08f
        private const val REST_LEAN = 0.05f
        private const val REST_LEAN_SPEED = 0.5f
        private const val ENTRANCE_FROM = 0.6f
        private const val ENTRANCE_OVERSHOOT = 1.70158f
        private const val ENTRANCE_SQUASH = 0.8f

        /**
         * How much of the character shows for [shown] (0 hidden .. 1 standing), as a scale about the
         * feet: it rises from 60 % with one overshoot. One curve for appearing and for going, so a
         * reversal mid-way is continuous; 0 at 0, so a hidden seat collapses to nothing.
         */
        fun entranceScale(shown: Float): Float {
            val t = shown.coerceIn(0f, 1f)
            if (t <= 0f) return 0f
            val s = t - 1f
            val backOut = 1f + s * s * ((ENTRANCE_OVERSHOOT + 1f) * s + ENTRANCE_OVERSHOOT)
            return ENTRANCE_FROM + (1f - ENTRANCE_FROM) * backOut
        }

        /**
         * The squash that goes with [entranceScale]: flat and wide low down, upright at the top. As
         * a log-scale like the body's: apply as scaleX * e^-d, scaleY * e^d. Zero once standing.
         */
        fun entranceDeform(shown: Float, tune: MascotBodyTune = MascotBodyTune()): Float {
            val t = shown.coerceIn(0f, 1f)
            return -ln(1f + tune.maxStretch) * ENTRANCE_SQUASH * sin(PI.toFloat() * t) * (1f - t)
        }
    }
}
