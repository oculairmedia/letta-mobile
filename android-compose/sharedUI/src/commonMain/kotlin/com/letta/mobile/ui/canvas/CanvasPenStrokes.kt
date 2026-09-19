package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import kotlin.math.abs

/**
 * A stroke being drawn with the pen.
 *
 * The pen is the one input that carries pressure, and a mouse event cannot hold it — so a pen
 * stroke is accumulated here, sample by sample, and handed to the board as one finished
 * [Element.Path] when the nib lifts. Each sample keeps its own width, which is what makes the line
 * swell and taper, and the tilt and azimuth the tablet reports ride along on the same samples.
 *
 * Building the whole stroke before submitting it also means one element and one undo step, rather
 * than a stroke assembled from whatever the mouse path happened to see.
 */
internal class CanvasPenStroke(
    private val strokeColor: androidx.compose.ui.graphics.Color,
    private val baseWidth: Float,
    private val strokeStyle: io.ak1.drawbox.domain.model.StrokeStyle,
    private val alpha: Float,
) {
    private val samples = mutableListOf<Element.PathSample>()
    private var last: Offset? = null

    /**
     * Adds a sample unless it lands on top of the previous one, and returns it so the board can
     * draw the stroke as it is being made. Without that the ink only appears on lift, and you are
     * drawing blind.
     */
    fun add(world: Offset, pressure: Float?): Element.PathSample? {
        val previous = last
        if (previous != null && abs(previous.x - world.x) < MIN_STEP && abs(previous.y - world.y) < MIN_STEP) {
            return null
        }
        last = world
        val sample = Element.PathSample(position = world, width = widthFor(pressure))
        samples += sample
        return sample
    }

    /**
     * The width this sample is drawn at.
     *
     * Pressure is not linear and rarely reaches its own maximum, so it is mapped onto a band around
     * the tool's width instead of scaling it outright: a light touch still leaves a line, and a
     * hard one does not blow out. A tool with no pressure axis draws at its nominal width.
     */
    private fun widthFor(pressure: Float?): Float {
        val force = pressure ?: return baseWidth
        val eased = force.coerceIn(0f, 1f)
        return baseWidth * (MIN_WIDTH_FACTOR + (MAX_WIDTH_FACTOR - MIN_WIDTH_FACTOR) * eased)
    }

    /** The finished stroke, or null when the pen never really moved. */
    fun finish(id: String): Element.Path? {
        if (samples.size < 2) return null
        return Element.Path(
            id = id,
            samples = samples.toList(),
            strokeColor = strokeColor,
            strokeWidth = baseWidth,
            alpha = alpha,
            strokeStyle = strokeStyle,
        )
    }

    private companion object {
        /** Samples closer together than this add nothing but weight. */
        const val MIN_STEP = 0.35f

        /** A barely-there touch still draws a visible hairline. */
        const val MIN_WIDTH_FACTOR = 0.25f

        /** Full force is a fat line, not an explosion. */
        const val MAX_WIDTH_FACTOR = 1.75f
    }
}

/** True when this mode draws freehand, which is the only mode a pen stroke belongs to. */
internal fun Mode.isFreehandDrawing(): Boolean = this == Mode.PEN

/** A stroke started with this board's current tool settings. */
internal fun State.beginPenStroke(): CanvasPenStroke = CanvasPenStroke(
    strokeColor = strokeColor,
    baseWidth = strokeWidth,
    strokeStyle = currentItemStrokeStyle,
    alpha = opacity,
)

/**
 * The stroke currently under the nib, drawn over the board until it is committed.
 *
 * DrawBox renders what it owns, and it does not own this stroke until the pen lifts, so the board
 * paints it here in the meantime. Each segment is drawn at the width its two samples call for,
 * which is what makes the pressure visible while you are still drawing rather than only after.
 */
@androidx.compose.runtime.Composable
internal fun CanvasPenPreview(
    samples: List<Element.PathSample>,
    viewport: io.ak1.drawbox.domain.model.Viewport,
    color: androidx.compose.ui.graphics.Color,
    alpha: Float,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    if (samples.size < 2) return
    androidx.compose.foundation.Canvas(modifier = modifier) {
        for (index in 0 until samples.lastIndex) {
            val from = samples[index]
            val to = samples[index + 1]
            drawLine(
                color = color,
                start = viewport.worldToScreen(from.position),
                end = viewport.worldToScreen(to.position),
                strokeWidth = ((from.width + to.width) / 2f) * viewport.scale,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                alpha = alpha.coerceIn(0f, 1f),
            )
        }
    }
}
