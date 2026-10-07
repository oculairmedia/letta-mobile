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

private enum class FadeAxis { Vertical, Horizontal }

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
    val vertical = edge.axis == FadeAxis.Vertical
    val extent = if (vertical) size.height else size.width
    val band = edge.length.toPx().coerceAtMost(extent / 2f)
    if (edge.alpha <= 0f || band <= 0f) return
    val start = if (edge.fromStart) 0f else extent - band
    val faded = Color.Black.copy(alpha = 1f - edge.alpha)
    val colors = if (edge.fromStart) listOf(faded, Color.Black) else listOf(Color.Black, faded)
    drawRect(
        brush = if (vertical) {
            Brush.verticalGradient(colors, startY = start, endY = start + band)
        } else {
            Brush.horizontalGradient(colors, startX = start, endX = start + band)
        },
        topLeft = if (vertical) Offset(0f, start) else Offset(start, 0f),
        size = if (vertical) Size(size.width, band) else Size(band, size.height),
        blendMode = BlendMode.DstIn,
    )
}
