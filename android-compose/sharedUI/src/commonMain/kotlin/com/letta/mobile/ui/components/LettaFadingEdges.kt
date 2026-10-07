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
 * edges (the agent rail, the desktop chat list). A [BlendMode.DstIn] vertical-gradient mask over an
 * offscreen layer: only the mask's alpha matters. Both ramps are anchored to the container's own
 * edges. No-ops (and skips the offscreen layer) when both alphas are 0, i.e. the list does not scroll.
 */
fun Modifier.lettaFadingEdges(
    topFadeAlpha: Float,
    bottomFadeAlpha: Float,
    topFadeLength: Dp,
    bottomFadeLength: Dp,
): Modifier {
    if (topFadeAlpha <= 0f && bottomFadeAlpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawFadeBand(topFadeLength.toPx(), topFadeAlpha, fromTop = true)
            drawFadeBand(bottomFadeLength.toPx(), bottomFadeAlpha, fromTop = false)
        }
}

/**
 * Masks one edge over [lengthPx], ramping the content out towards it. Drawn as a band rather than
 * over the whole content: a gradient brush clamps to its end colours outside `[startY, endY]`.
 */
private fun DrawScope.drawFadeBand(lengthPx: Float, alpha: Float, fromTop: Boolean) {
    val band = lengthPx.coerceAtMost(size.height / 2f)
    if (alpha <= 0f || band <= 0f) return
    val startY = if (fromTop) 0f else size.height - band
    val faded = Color.Black.copy(alpha = 1f - alpha)
    drawRect(
        brush = Brush.verticalGradient(
            colors = if (fromTop) listOf(faded, Color.Black) else listOf(Color.Black, faded),
            startY = startY,
            endY = startY + band,
        ),
        topLeft = Offset(0f, startY),
        size = Size(size.width, band),
        blendMode = BlendMode.DstIn,
    )
}
