package com.letta.mobile.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
 * [pinnedHeaderHeightPx] — read fresh on every draw — is the height in px of
 * content at the very top that must stay untouched by the top fade (e.g. a
 * pinned sticky header card); the top gradient starts below it instead of at
 * y=0. Defaults to always 0 (fade starts at the top edge), which is also
 * what callers with no such pinned content get for free.
 */
internal fun Modifier.fadingEdges(
    topFadeAlpha: Float,
    bottomFadeAlpha: Float,
    topFadeLength: Dp,
    bottomFadeLength: Dp,
    pinnedHeaderHeightPx: () -> Float = { 0f },
): Modifier {
    if (topFadeAlpha <= 0f && bottomFadeAlpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (topFadeAlpha > 0f) {
                val topFadePx = topFadeLength.toPx()
                val topStartY = pinnedHeaderHeightPx().coerceIn(0f, size.height)
                if (topFadePx > 0f && topStartY < size.height) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 1f - topFadeAlpha), Color.Black),
                            startY = topStartY,
                            endY = (topStartY + topFadePx).coerceAtMost(size.height),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            if (bottomFadeAlpha > 0f) {
                val bottomFadePx = bottomFadeLength.toPx()
                if (bottomFadePx > 0f) {
                    val len = bottomFadePx.coerceAtMost(size.height / 2f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - bottomFadeAlpha)),
                            startY = size.height - len,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
}
