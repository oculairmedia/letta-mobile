package com.letta.mobile.avatar.core

import kotlin.math.sqrt

/** Axis-aligned surface in the same space as a pointer (window or stage pixels). */
data class GazeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/** A point in gaze units: -1..1, origin at the mascot, +x right, +y down (screen). */
data class GazePoint(val x: Float, val y: Float) {
    fun coerce(): GazePoint = GazePoint(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
}

/**
 * Pixel → gaze mapping used by live product surfaces. Reach is a window-scale
 * distance (at least [minReachPx]), never the tile's own width, so a 32 dp
 * tile does not saturate for a cursor a few px away; saturation is smooth and
 * stops short of the rim so the eyes never sit pinned.
 *
 * The bench uses a linear mascot-width reach (`trackReach`) instead; that
 * conversion stays at the spike. This object is the product mapping.
 */
object GazeMath {
    /** The body spans ~60 % of the artboard; product tiles use this fraction of surface width. */
    const val TILE_REACH: Float = 1.25f

    /** Soft saturation ceiling so the eyes never sit pinned at the rim. */
    const val GAZE_MAX: Float = 0.85f

    /** Linear near zero, asymptotic to +-[GAZE_MAX]. */
    fun softLook(d: Float): Float = GAZE_MAX * d / sqrt(1f + d * d)

    fun pointerToGaze(x: Float, y: Float, bounds: GazeRect, minReachPx: Float): GazePoint {
        if (bounds.isEmpty) return GazePoint(0f, 0f)
        val reach = maxOf(bounds.width * TILE_REACH, minReachPx)
        return GazePoint(
            x = softLook((x - bounds.centerX) / reach),
            y = softLook((y - bounds.centerY) / reach),
        )
    }

    /**
     * Host input / timeline surfaces: map a window-space rect's centre through
     * [pointerToGaze] against the mascot tile. Null or empty rects stay
     * unavailable so the plan skips that row (OWN / USER / CURSOR still run).
     */
    fun rectCenterToGaze(target: GazeRect?, mascot: GazeRect, minReachPx: Float): GazePoint? {
        if (target == null) return null
        if (target.isEmpty) return null
        if (mascot.isEmpty) return null
        return pointerToGaze(target.centerX, target.centerY, mascot, minReachPx)
    }

    /** Window-space pointer, or null when the cursor has left / the tile has no size. */
    fun pointerPxToGaze(pointerPx: GazePoint?, mascot: GazeRect, minReachPx: Float): GazePoint? {
        if (pointerPx == null) return null
        if (mascot.isEmpty) return null
        return pointerToGaze(pointerPx.x, pointerPx.y, mascot, minReachPx)
    }

    /** Screen space is 0..1; the runtime maps it back to the contract's -1..1. */
    fun toScreen(point: GazePoint): AvatarLookTarget.Screen =
        AvatarLookTarget.Screen((point.x + 1f) / 2f, (point.y + 1f) / 2f)
}
