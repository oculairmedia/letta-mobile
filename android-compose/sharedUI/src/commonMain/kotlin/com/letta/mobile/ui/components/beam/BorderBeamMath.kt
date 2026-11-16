package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The beam's pure maths, shared by the shader and the Canvas fallback so both draw the same beam.
 * Ported from border-beam-android by ismnoiet (MIT, see sharedUI/THIRD_PARTY_LICENSES).
 */

/** The most colours a beam blends; the shader declares a fixed array of this size. */
internal const val MAX_BEAM_COLORS = 8

/** RGBA floats per packed palette colour. */
private const val CHANNELS = 4

/** The longest frame gap the phase follows; a longer pause (a stall, a debugger) resumes in place. */
private const val MAX_FRAME_SECONDS = 0.1f

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MILLIS_PER_SECOND = 1_000f

/** How long the comet is as a fraction of the lap: [LettaBeamStyle.OrbitCompact] is longer and tighter. */
internal val LettaBeamStyle.arc: Float
    get() = if (this == LettaBeamStyle.OrbitCompact) COMPACT_ARC else ORBIT_ARC

private const val ORBIT_ARC = 0.36f
private const val COMPACT_ARC = 0.5f

/** The share of the width a sweeping beam covers. */
internal const val SWEEP_LENGTH = 0.28f

/**
 * At most [max] colours, never none: a longer palette is thinned evenly and keeps its first (tail)
 * and last (head) colour; an empty one becomes a single white so the beam still draws.
 */
internal fun fitBeamPalette(colors: List<Color>, max: Int = MAX_BEAM_COLORS): List<Color> {
    val source = colors.ifEmpty { listOf(Color.White) }
    if (source.size <= max) return source
    return List(max) { index -> source[index * (source.size - 1) / (max - 1)] }
}

/** [colors] fitted and flattened to sRGB RGBA floats, padded to [MAX_BEAM_COLORS] for the shader's array. */
internal fun packBeamPalette(colors: List<Color>): FloatArray {
    val packed = FloatArray(MAX_BEAM_COLORS * CHANNELS)
    fitBeamPalette(colors).forEachIndexed { index, color ->
        val srgb = color.convert(ColorSpaces.Srgb)
        val base = index * CHANNELS
        packed[base] = srgb.red
        packed[base + 1] = srgb.green
        packed[base + 2] = srgb.blue
        packed[base + 3] = srgb.alpha
    }
    return packed
}

/** The palette blended linearly at [t] (0 = tail, 1 = head). */
internal fun sampleBeamPalette(palette: List<Color>, t: Float): Color {
    if (palette.size == 1) return palette[0]
    val x = t.coerceIn(0f, 1f) * (palette.size - 1)
    val index = min(x.toInt(), palette.size - 2)
    return lerp(palette[index], palette[index + 1], x - index)
}

/** Brightness along the beam's length: 0 at both ends, 1 in the middle, sharper for a larger [power]. */
internal fun beamBell(t: Float, power: Float): Float = max(sin(PI * t), 0.0).pow(power.toDouble()).toFloat()

/**
 * The phase after a frame [frameNanos] long, for a beam that takes [periodMillis] per lap. The phase
 * advances frame by frame (rather than being derived from the clock) so a beam that is paused and
 * resumed carries on where it was. Gaps longer than a tenth of a second count as a tenth.
 */
internal fun advanceBeamPhase(phase: Float, frameNanos: Long, periodMillis: Int): Float {
    val seconds = (frameNanos / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_SECONDS)
    val lapsPerSecond = MILLIS_PER_SECOND / max(periodMillis, 1)
    return (phase + seconds * lapsPerSecond) % 1f
}

/**
 * Where a sweeping beam's leading edge sits as a fraction of the free width: a there-and-back pass
 * eased in and out at both ends (0 at phase 0 and 1, 1 at phase 0.5).
 */
internal fun sweepPosition(phase: Float): Float {
    val p = phase.mod(1f)
    val triangle = if (p < HALF) p * 2f else (1f - p) * 2f
    return triangle * triangle * (3f - 2f * triangle)
}

private const val HALF = 0.5f
