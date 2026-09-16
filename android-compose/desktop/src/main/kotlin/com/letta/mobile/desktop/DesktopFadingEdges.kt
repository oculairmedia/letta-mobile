package com.letta.mobile.desktop

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
 * Softly dissolves the top [topFadeLength] and bottom [bottomFadeLength] of
 * the wrapped content to transparent, so a scrolling list grades into the
 * surrounding chrome instead of hard-clipping at its edges — shared by the
 * desktop chat message list and the agent rail (the desktop port of the
 * mobile chat fading edges). A [BlendMode.DstIn] vertical-gradient mask over
 * an offscreen layer (DstIn keeps the already-drawn content only where the
 * mask is opaque, so a transparent→opaque ramp makes each edge fade out).
 * The mask colour is irrelevant; only its alpha drives the fade. No-ops (and
 * skips the offscreen layer) when both alphas are 0, i.e. the list isn't
 * scrollable.
 *
 * Both ramps are anchored to the container's own edges. An earlier version let
 * the top ramp start below a pinned sticky header so the header stayed opaque;
 * that put a dissolved band across the MIDDLE of the viewport — content
 * directly under the pinned card vanished while content above it stayed sharp,
 * which reads as a rendering bug rather than an edge treatment. Content that
 * must not fade is kept out of this modifier's subtree, or the caller drops
 * the relevant alpha to 0 (see the chat list's pinned-prompt handling).
 */
internal fun Modifier.fadingEdges(
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
 * Masks one edge over [lengthPx], ramping the content out towards it. Drawn as
 * a BAND rather than over the whole content: a gradient brush clamps to its end
 * colours outside `[startY, endY]`, so a full-size rect would carry the ramp's
 * transparent end across every pixel beyond it.
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

/**
 * The horizontal counterpart of [fadingEdges], for rows that scroll sideways.
 *
 * A horizontally scrolling strip that hard-clips at the pane edge reads as a
 * layout bug — the last item looks broken rather than scrollable. Ramping the
 * edge out says "there is more this way" with no extra chrome.
 */
internal fun Modifier.horizontalFadingEdges(
    startFadeAlpha: Float,
    endFadeAlpha: Float,
    fadeLength: Dp,
): Modifier {
    if (startFadeAlpha <= 0f && endFadeAlpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawHorizontalFadeBand(fadeLength.toPx(), startFadeAlpha, fromStart = true)
            drawHorizontalFadeBand(fadeLength.toPx(), endFadeAlpha, fromStart = false)
        }
}

/** As [drawFadeBand], along x. */
private fun DrawScope.drawHorizontalFadeBand(lengthPx: Float, alpha: Float, fromStart: Boolean) {
    val band = lengthPx.coerceAtMost(size.width / 2f)
    if (alpha <= 0f || band <= 0f) return
    val startX = if (fromStart) 0f else size.width - band
    val faded = Color.Black.copy(alpha = 1f - alpha)
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (fromStart) listOf(faded, Color.Black) else listOf(Color.Black, faded),
            startX = startX,
            endX = startX + band,
        ),
        topLeft = Offset(startX, 0f),
        size = Size(band, size.height),
        blendMode = BlendMode.DstIn,
    )
}
