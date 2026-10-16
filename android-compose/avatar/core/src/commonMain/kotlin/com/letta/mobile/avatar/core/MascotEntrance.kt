package com.letta.mobile.avatar.core

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * How a mascot comes in and goes (letta-mobile-te3n1.3), as curves of how much of it shows:
 * 0 hidden .. 1 standing. One curve for appearing and for going, so a reversal mid-way is
 * continuous.
 */
object MascotEntrance {
    private const val FROM = 0.6f
    private const val OVERSHOOT = 1.70158f
    private const val SQUASH = 0.8f
    private val maxDeform = ln(1f + MascotBodyTune().maxStretch)

    /**
     * The scale about the feet for [shown]: it rises from 60 % with one overshoot; 0 at 0, so a
     * hidden seat collapses to nothing.
     */
    fun scale(shown: Float): Float {
        val t = shown.coerceIn(0f, 1f)
        if (t <= 0f) return 0f
        val s = t - 1f
        val backOut = 1f + s * s * ((OVERSHOOT + 1f) * s + OVERSHOOT)
        return FROM + (1f - FROM) * backOut
    }

    /**
     * The squash that goes with [scale]: flat and wide low down, upright at the top. A log-scale
     * like the body's (apply as scaleX * e^-d, scaleY * e^d); zero once standing.
     */
    fun deform(shown: Float): Float {
        val t = shown.coerceIn(0f, 1f)
        return -maxDeform * SQUASH * sin(PI.toFloat() * t) * (1f - t)
    }
}
