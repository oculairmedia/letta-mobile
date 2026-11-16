package com.letta.mobile.ui.components.beam

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import kotlin.math.max

/**
 * The beam drawn with plain Canvas strokes, where no runtime shader runs (Android 12 and older, or a
 * shader that failed to compile). The comet is short stroked segments laid along the border, each
 * drawn three times (soft glow, halo, core) in its palette colour and faded toward both ends. It
 * follows the shader's geometry and palette but approximates its per-pixel glow.
 *
 * One instance per beam: the outline path and its measure are rebuilt only when the size or corner
 * radius changes, and the segment path is reused, so a frame allocates only its stroke styles.
 * Ported from border-beam-android by ismnoiet (MIT, see sharedUI/THIRD_PARTY_LICENSES).
 */
internal class BorderBeamFallback : BorderBeamRenderer {
    private var cachedSize = Size.Unspecified
    private var cachedRadius = Float.NaN
    private val clip = Path()
    private val track = Path()
    private val measure = PathMeasure()
    private val segment = Path()

    override fun DrawScope.drawBeam(frame: BeamFrame) {
        if (size.width <= 0f || size.height <= 0f) return
        ensureOutline(frame.cornerRadiusPx)
        val strength = frame.alpha.coerceIn(0f, 1f)
        val strokes = BeamStrokes(this, frame.style)
        clipPath(clip) {
            if (frame.style == LettaBeamStyle.Sweep) {
                drawSweep(frame, strength, strokes)
            } else {
                drawOrbit(frame, strength, strokes)
            }
        }
    }

    private fun DrawScope.ensureOutline(radius: Float) {
        if (size == cachedSize && radius == cachedRadius) return
        cachedSize = size
        cachedRadius = radius
        clip.rewind()
        clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        // The comet runs one hairline inside the edge, so its core is not half clipped away.
        val inset = INSET_DP * density
        track.rewind()
        track.addRoundRect(
            RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(max(radius - inset, 0f))),
        )
        measure.setPath(track, false)
    }

    /** The comet circling the border, segment by segment from tail to head. */
    private fun DrawScope.drawOrbit(frame: BeamFrame, strength: Float, strokes: BeamStrokes) {
        val length = measure.length
        if (length <= 0f) return
        val arc = frame.style.arc
        val sign = frame.direction.sign
        for (k in 0 until SEGMENTS) {
            val t = (k + CENTRE) / SEGMENTS
            val color = sampleBeamPalette(frame.palette.colors, t)
            val weight = beamBell(t, BELL_POWER) * color.alpha * strength
            if (weight <= MIN_WEIGHT) continue
            val position = ((1f - arc) + k * arc / SEGMENTS + sign * frame.phase).mod(1f)
            val start = position * length
            // A little overlap so neighbouring segments join without gaps.
            val end = start + arc * length / SEGMENTS * OVERLAP
            // The segment's colour carries its weight as alpha; each stroke scales that.
            val tinted = color.copy(alpha = weight)
            if (end <= length) {
                strokeSegment(start, end, tinted, strokes)
            } else {
                strokeSegment(start, length, tinted, strokes)
                strokeSegment(0f, end - length, tinted, strokes)
            }
        }
    }

    /** Strokes the border from [from] to [to] (px along it); [tinted]'s alpha is the segment's weight. */
    private fun DrawScope.strokeSegment(from: Float, to: Float, tinted: Color, strokes: BeamStrokes) {
        segment.rewind()
        measure.getSegment(from, to, segment, true)
        val weight = tinted.alpha
        drawPath(segment, tinted.copy(alpha = GLOW_ALPHA * weight), style = strokes.glow)
        drawPath(segment, tinted.copy(alpha = HALO_ALPHA * weight), style = strokes.halo)
        drawPath(segment, tinted, style = strokes.core)
    }

    /** The beam sweeping back and forth along the bottom edge. */
    private fun DrawScope.drawSweep(frame: BeamFrame, strength: Float, strokes: BeamStrokes) {
        val width = size.width
        val y = size.height - INSET_DP * density
        val length = width * SWEEP_LENGTH
        val start = (width - length) * sweepPosition(frame.phase)
        for (k in 0 until SEGMENTS) {
            val t = (k + CENTRE) / SEGMENTS
            val color = sampleBeamPalette(frame.palette.colors, t)
            val weight = beamBell(t, BELL_POWER) * color.alpha * strength
            if (weight <= MIN_WEIGHT) continue
            val from = Offset(start + length * k / SEGMENTS, y)
            val to = Offset(start + length * (k + OVERLAP) / SEGMENTS, y)
            val rgb = color.copy(alpha = 1f)
            drawLine(rgb.copy(alpha = GLOW_ALPHA * weight), from, to, strokes.glow.width, StrokeCap.Round)
            drawLine(rgb.copy(alpha = HALO_ALPHA * weight), from, to, strokes.halo.width, StrokeCap.Round)
            drawLine(rgb.copy(alpha = weight), from, to, strokes.core.width, StrokeCap.Round)
        }
    }

    private companion object {
        const val SEGMENTS = 36
        const val CENTRE = 0.5f
        const val OVERLAP = 1.2f
        const val BELL_POWER = 1.2f
        const val MIN_WEIGHT = 0.01f
        const val GLOW_ALPHA = 0.22f
        const val HALO_ALPHA = 0.45f
        const val GLOW_SHARE = 0.9f
        const val INSET_DP = 1f
    }

    /** The three stroke widths of one frame, in px. */
    private class BeamStrokes(scope: DrawScope, style: LettaBeamStyle) {
        val glow: Stroke
        val halo: Stroke
        val core: Stroke

        init {
            with(scope) {
                val glowWidth = max(style.glowReach.toPx() * GLOW_SHARE, LettaComponentDimens.beamMinGlowStroke.toPx())
                glow = Stroke(glowWidth, cap = StrokeCap.Round)
                halo = Stroke(LettaComponentDimens.beamHaloStroke.toPx(), cap = StrokeCap.Round)
                core = Stroke(LettaComponentDimens.beamCoreStroke.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
