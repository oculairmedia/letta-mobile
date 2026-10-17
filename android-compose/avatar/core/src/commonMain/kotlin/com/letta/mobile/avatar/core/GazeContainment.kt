package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SPEC §10.4 combined gaze containment, host side (letta-mobile-kkjyd, absorbed by te3n1.2).
 *
 * The rig moves the eye glyph by H = (23·lookX, 17·lookY) artboard px on the standard 120 px / r27
 * plate, and the file adds its own decorative offset (auto bias, microsaccade) on top. The host's
 * scans ([GazeDirector]'s reading / typing saccades) are decorative too. The rule: keep the host's
 * intent H first and attenuate the decorative noise N by the greatest λ∈[0,1] for which H+λN keeps
 * ≥2 px inward clearance; if H alone does not fit, drop N and scale H by the greatest κ∈[0,1].
 *
 * The host cannot see the file's own N or the current glyph's outline, so it reserves both:
 * [GLYPH_REACH_PX] (the widest glyph's ink reach, from its 8.83 px nominal clearance at the
 * ±23/±17 corners, art/validation/max) and [FILE_NOISE_RESERVE_PX] (the file's decorative reach,
 * sized so an unattenuated extreme look plus saccade reproduces §10.4's 2.54 px overflow). All
 * arithmetic is on floats: called every tick, it allocates nothing.
 */
object GazeContainment {
    const val LOOK_X_PX: Float = 23f
    const val LOOK_Y_PX: Float = 17f
    const val PLATE_INNER_HALF_PX: Float = 33f
    const val PLATE_RADIUS_PX: Float = 27f
    const val CLEARANCE_PX: Float = 2f
    const val GLYPH_REACH_PX: Float = 28.17f
    const val FILE_NOISE_RESERVE_PX: Float = 11.37f

    private const val SEARCH_STEPS = 10

    /** Signed distance (artboard px) from the eye centre at ([px], [py]) to the plate's rounded outline; negative inside. */
    fun plateDistance(px: Float, py: Float): Float {
        val qx = abs(px) - PLATE_INNER_HALF_PX
        val qy = abs(py) - PLATE_INNER_HALF_PX
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - PLATE_RADIUS_PX
    }

    /** True when an eye looking at ([lookX], [lookY]) keeps its ink, plus the file's reserve, ≥2 px inside the plate. */
    fun fits(lookX: Float, lookY: Float): Boolean =
        plateDistance(LOOK_X_PX * lookX, LOOK_Y_PX * lookY) + GLYPH_REACH_PX + FILE_NOISE_RESERVE_PX <= -CLEARANCE_PX

    /** The greatest κ∈[0,1] for which κ·[host] fits; 1 when it already does. */
    fun hostScale(host: GazePoint): Float {
        if (fits(host.x, host.y)) return 1f
        return greatestFit(0f, 0f, host.x, host.y)
    }

    /** The greatest λ∈[0,1] for which [host] + λ·([noiseX], [noiseY]) fits; 0 when [host] alone does not. */
    fun noiseScale(host: GazePoint, noiseX: Float, noiseY: Float): Float {
        if (fits(host.x + noiseX, host.y + noiseY)) return 1f
        if (!fits(host.x, host.y)) return 0f
        return greatestFit(host.x, host.y, noiseX, noiseY)
    }

    /** Bisection for the largest t in 0..1 with (fromX + t·dx, fromY + t·dy) inside; the plate is convex. */
    private fun greatestFit(fromX: Float, fromY: Float, dx: Float, dy: Float): Float {
        var inside = 0f
        var outside = 1f
        repeat(SEARCH_STEPS) {
            val mid = (inside + outside) / 2f
            if (fits(fromX + mid * dx, fromY + mid * dy)) inside = mid else outside = mid
        }
        return inside
    }
}
