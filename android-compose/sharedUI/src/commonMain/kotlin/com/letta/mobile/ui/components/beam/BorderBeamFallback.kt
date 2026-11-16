package com.letta.mobile.ui.components.beam

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max

/**
 * The beam drawn with plain Canvas strokes, where no runtime shader runs (Android 12 and older, or a
 * shader that failed to compile). The comet is one continuous gradient stroke laid along the border,
 * drawn three times (soft glow, halo, core) and faded toward both ends. It
 * follows the shader's geometry and palette but approximates its per-pixel glow.
 *
 * One instance per beam: the outline path and its measure are rebuilt only when the size or corner
 * radius changes. The track path and stroke styles are reused across frames. Palette stops are cached;
 * each orbit frame submits exactly three strokes of the cached track. A sweep gradient maps
 * palette stops to cached perimeter angles, so colour and brightness follow corners too.
 * Ported from border-beam-android by ismnoiet (MIT, see sharedUI/THIRD_PARTY_LICENSES).
 */
internal class BorderBeamFallback : BorderBeamRenderer {
    private var cachedSize = Size.Unspecified
    private val cachedCorners = FloatArray(CORNERS) { Float.NaN }
    private val clip = Path()
    private val track = Path()
    private val measure = PathMeasure()
    private val perimeterAngles = FloatArray(ANGLE_SAMPLES + 1)
    private var strokeDensity = Float.NaN
    private var strokeStyle: LettaBeamStyle? = null
    private var cachedStrokes: BeamStrokes? = null
    private var cachedPalette: BeamPalette? = null
    private var gradientColors: List<Color> = emptyList()

    override fun DrawScope.drawBeam(frame: BeamFrame) {
        if (size.width <= 0f || size.height <= 0f) return
        ensureOutline(frame.cornersPx)
        ensurePalette(frame.palette)
        val strength = frame.alpha.coerceIn(0f, 1f)
        val strokes = strokesFor(frame.style)
        clipPath(clip) {
            if (frame.style == LettaBeamStyle.Sweep) {
                drawSweep(frame, strength, strokes)
            } else {
                drawOrbit(frame, strength, strokes)
            }
        }
    }

    /** Palette blending and the comet's bell are independent of phase and fade. */
    private fun ensurePalette(palette: BeamPalette) {
        if (cachedPalette === palette) return
        cachedPalette = palette
        gradientColors = List(PALETTE_STOPS) { index ->
            val t = index.toFloat() / (PALETTE_STOPS - 1)
            val color = sampleBeamPalette(palette.colors, t)
            color.copy(alpha = beamBell(t, BELL_POWER) * color.alpha)
        }
    }

    /** Density and style, rather than the moving phase, determine the strokes. */
    private fun DrawScope.strokesFor(style: LettaBeamStyle): BeamStrokes {
        if (strokeDensity != density || strokeStyle != style) {
            cachedStrokes = BeamStrokes(this, style)
            strokeDensity = density
            strokeStyle = style
        }
        return checkNotNull(cachedStrokes)
    }

    private fun DrawScope.ensureOutline(corners: FloatArray) {
        if (size == cachedSize && corners.contentEquals(cachedCorners)) return
        cachedSize = size
        corners.copyInto(cachedCorners)
        val tr = corners[0]
        val br = corners[1]
        val bl = corners[2]
        val tl = corners[3]
        clip.rewind()
        clip.addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(tl),
                topRightCornerRadius = CornerRadius(tr),
                bottomRightCornerRadius = CornerRadius(br),
                bottomLeftCornerRadius = CornerRadius(bl),
            ),
        )
        // The comet runs one hairline inside the edge, so its core is not half clipped away.
        val inset = INSET_DP * density
        track.rewind()
        track.addBeamTrack(Rect(inset, inset, size.width - inset, size.height - inset), corners, inset)
        measure.setPath(track, false)
        for (index in 0 until ANGLE_SAMPLES) {
            val point = measure.getPosition(measure.length * index / ANGLE_SAMPLES) - center
            perimeterAngles[index] = (atan2(point.y, point.x) / FULL_TURN).mod(1f)
        }
        perimeterAngles[ANGLE_SAMPLES] = 1f
    }

    /** Maps perimeter distance to angle without measuring paths or taking trigonometry each frame. */
    private fun perimeterAngle(position: Float): Float {
        val sample = position.mod(1f) * ANGLE_SAMPLES
        val index = sample.toInt()
        val fraction = sample - index
        return perimeterAngles[index] + (perimeterAngles[index + 1] - perimeterAngles[index]) * fraction
    }

    /** A transparent sweep outside the comet; its coloured stops follow the perimeter distance. */
    @Suppress("SpreadOperator") // Compose requires varargs for nonuniform stops; bounded to 27 entries.
    private fun DrawScope.drawOrbit(frame: BeamFrame, strength: Float, strokes: BeamStrokes) {
        if (measure.length <= 0f) return
        val start = ((1f - frame.style.arc) + frame.direction.sign * frame.phase).mod(1f)
        val stops = gradientColors.mapIndexed { index, color ->
            perimeterAngle(start + frame.style.arc * index / (PALETTE_STOPS - 1)) to color
        }.sortedBy { it.first }
        val seam = if (start + frame.style.arc > 1f) {
            sampleBeamPalette(gradientColors, (1f - start) / frame.style.arc)
        } else {
            Color.Transparent
        }
        val brush = Brush.sweepGradient(
            *(listOf(0f to seam) + stops + (1f to seam)).toTypedArray(),
            center = center,
        )
        drawPath(track, brush, alpha = GLOW_ALPHA * strength, style = strokes.glow)
        drawPath(track, brush, alpha = HALO_ALPHA * strength, style = strokes.halo)
        drawPath(track, brush, alpha = strength, style = strokes.core)
    }

    /** A continuous gradient sweeping back and forth along the bottom edge. */
    private fun DrawScope.drawSweep(frame: BeamFrame, strength: Float, strokes: BeamStrokes) {
        val width = size.width
        val y = size.height - INSET_DP * density
        val length = width * SWEEP_LENGTH
        val start = (width - length) * sweepPosition(frame.phase)
        val from = Offset(start, y)
        val to = Offset(start + length, y)
        val brush = Brush.linearGradient(gradientColors, from, to)
        drawLine(brush, from, to, strokes.glow.width, StrokeCap.Round, alpha = GLOW_ALPHA * strength)
        drawLine(brush, from, to, strokes.halo.width, StrokeCap.Round, alpha = HALO_ALPHA * strength)
        drawLine(brush, from, to, strokes.core.width, StrokeCap.Round, alpha = strength)
    }

    private companion object {
        const val ANGLE_SAMPLES = 256
        val FULL_TURN = (2 * PI).toFloat()
        const val PALETTE_STOPS = 25
        const val BELL_POWER = 1.2f
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

/**
 * The border the comet travels, walked the way the shader measures it: clockwise on screen from the
 * right edge's midpoint, through the bottom-right, bottom-left, top-left and top-right corners. So
 * phase 0 sits at the same point in both renderers, and distance along the path grows in the
 * clockwise direction on every platform (Compose's own rounded-rectangle paths wind the other way
 * and start elsewhere). [corners] are the outer radii in [BeamFrame.cornersPx] order; each shrinks
 * by [inset] to stay concentric with the edge.
 */
internal fun Path.addBeamTrack(bounds: Rect, corners: FloatArray, inset: Float) {
    val inner = corners.map { max(it - inset, 0f) }
    val tr = inner[0]
    val br = inner[1]
    val bl = inner[2]
    val tl = inner[3]
    moveTo(bounds.right, bounds.center.y)
    lineTo(bounds.right, bounds.bottom - br)
    cornerArc(Offset(bounds.right - br, bounds.bottom - br), br, startDegrees = 0f)
    lineTo(bounds.left + bl, bounds.bottom)
    cornerArc(Offset(bounds.left + bl, bounds.bottom - bl), bl, startDegrees = 90f)
    lineTo(bounds.left, bounds.top + tl)
    cornerArc(Offset(bounds.left + tl, bounds.top + tl), tl, startDegrees = 180f)
    lineTo(bounds.right - tr, bounds.top)
    cornerArc(Offset(bounds.right - tr, bounds.top + tr), tr, startDegrees = 270f)
    close()
}

/** A clockwise quarter circle of [radius] around [centre] from [startDegrees]; nothing for a square corner. */
private fun Path.cornerArc(centre: Offset, radius: Float, startDegrees: Float) {
    if (radius <= 0f) return
    arcTo(Rect(centre, radius), startDegrees, QUARTER_TURN, forceMoveTo = false)
}

private const val QUARTER_TURN = 90f

