package com.letta.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Softly dissolves the top [topFadeLength] and bottom [bottomFadeLength] of the wrapped content to
 * transparent, so a scrolling list grades into the surrounding chrome instead of hard-clipping at its
 * edges (the agent rail, the desktop chat list). A [BlendMode.DstIn] gradient mask over an offscreen
 * layer: only the mask's alpha matters. Both ramps are anchored to the container's own edges. No-ops
 * (and skips the offscreen layer) when both alphas are 0, i.e. the list does not scroll.
 */
fun Modifier.lettaFadingEdges(
    topFadeAlpha: Float,
    bottomFadeAlpha: Float,
    topFadeLength: Dp,
    bottomFadeLength: Dp,
): Modifier = fadeEdges(
    FadeEdge(FadeAxis.Vertical, fromStart = true, alpha = topFadeAlpha, length = topFadeLength),
    FadeEdge(FadeAxis.Vertical, fromStart = false, alpha = bottomFadeAlpha, length = bottomFadeLength),
)

/**
 * The horizontal counterpart of [lettaFadingEdges], for rows that scroll sideways: a strip that
 * hard-clips at the pane edge reads as a layout bug, a ramp says "there is more this way".
 */
fun Modifier.lettaHorizontalFadingEdges(
    startFadeAlpha: Float,
    endFadeAlpha: Float,
    fadeLength: Dp,
): Modifier = fadeEdges(
    FadeEdge(FadeAxis.Horizontal, fromStart = true, alpha = startFadeAlpha, length = fadeLength),
    FadeEdge(FadeAxis.Horizontal, fromStart = false, alpha = endFadeAlpha, length = fadeLength),
)

/** How a band lies along each axis: its extent in the content, its gradient, and where it is drawn. */
private enum class FadeAxis {
    Vertical {
        override fun extent(size: Size) = size.height
        override fun brush(colors: List<Color>, band: Band) = Brush.verticalGradient(colors, startY = band.start, endY = band.end)
        override fun topLeft(band: Band) = Offset(0f, band.start)
        override fun bandSize(size: Size, band: Band) = Size(size.width, band.length)
    },
    Horizontal {
        override fun extent(size: Size) = size.width
        override fun brush(colors: List<Color>, band: Band) = Brush.horizontalGradient(colors, startX = band.start, endX = band.end)
        override fun topLeft(band: Band) = Offset(band.start, 0f)
        override fun bandSize(size: Size, band: Band) = Size(band.length, size.height)
    },
    ;

    abstract fun extent(size: Size): Float
    abstract fun brush(colors: List<Color>, band: Band): Brush
    abstract fun topLeft(band: Band): Offset
    abstract fun bandSize(size: Size, band: Band): Size
}

/** Where a band starts along its axis, and how long it is, in px. */
private class Band(val start: Float, val length: Float) {
    val end: Float get() = start + length
}

private class FadeEdge(val axis: FadeAxis, val fromStart: Boolean, val alpha: Float, val length: Dp)

private fun Modifier.fadeEdges(first: FadeEdge, second: FadeEdge): Modifier {
    if (first.alpha <= 0f && second.alpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawFadeBand(first)
            drawFadeBand(second)
        }
}

/**
 * Masks one edge, ramping the content out towards it. Drawn as a band rather than over the whole
 * content: a gradient brush clamps to its end colours outside its range.
 */
private fun DrawScope.drawFadeBand(edge: FadeEdge) {
    val axis = edge.axis
    val extent = axis.extent(size)
    val length = edge.length.toPx().coerceAtMost(extent / 2f)
    if (edge.alpha <= 0f || length <= 0f) return
    val band = Band(start = if (edge.fromStart) 0f else extent - length, length = length)
    val faded = Color.Black.copy(alpha = 1f - edge.alpha)
    val colors = if (edge.fromStart) listOf(faded, Color.Black) else listOf(Color.Black, faded)
    drawRect(
        brush = axis.brush(colors, band),
        topLeft = axis.topLeft(band),
        size = axis.bandSize(size, band),
        blendMode = BlendMode.DstIn,
    )
}
