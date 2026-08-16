package com.letta.mobile.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
            // Each mask is drawn as a BAND, not over the whole content: a
            // gradient brush clamps to its end colours outside [startY, endY],
            // so a full-size rect would carry the top ramp's transparent end
            // across every pixel above it.
            if (topFadeAlpha > 0f) {
                val topEndY = topFadeLength.toPx().coerceAtMost(size.height / 2f)
                if (topEndY > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 1f - topFadeAlpha), Color.Black),
                            startY = 0f,
                            endY = topEndY,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, topEndY),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            if (bottomFadeAlpha > 0f) {
                val len = bottomFadeLength.toPx().coerceAtMost(size.height / 2f)
                if (len > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - bottomFadeAlpha)),
                            startY = size.height - len,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - len),
                        size = Size(size.width, len),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
}
