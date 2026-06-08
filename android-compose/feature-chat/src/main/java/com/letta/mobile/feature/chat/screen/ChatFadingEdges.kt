package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaSpacing

// letta-mobile-58qlr: soft gradient fading edges at the top and bottom of the
// chat message list, replacing the harsh scroll-clip line.
//
// Implementation (Option B, hand-rolled, zero new deps): a draw-layer overlay
// using Modifier.drawWithContent + Brush.verticalGradient mask blended with
// BlendMode.DstIn. DstIn keeps the destination (the already-drawn list) only
// where the mask is opaque, so a transparent->background gradient at each edge
// makes content dissolve into the chat background instead of cutting off.
//
// Perf: this is a DRAW-LAYER overlay only. The fade lengths are constants and
// the gradient brushes are remembered (keyed on density + fade length + color),
// so the draw lambda allocates nothing per frame and triggers NO relayout /
// recomposition while scrolling (no rmzmo regression). The graphicsLayer with
// CompositingStrategy.Offscreen is required for BlendMode.DstIn to compose
// against the node's own pixels rather than the screen.
//
// Pinch interplay: the LazyColumn already keeps an identity graphicsLayer for
// offscreen rasterization during pinch (letta-mobile-erhjl). This fade is
// applied on a WRAPPING Box around the LazyColumn — a separate node — so the
// DstIn offscreen layer here does not clobber the list's own rasterization
// layer. The fade simply masks whatever the (possibly pinch-rasterized) list
// drew, which composes cleanly.

/**
 * Whether the TOP fade should be drawn: only when there is content scrolled
 * off the top edge to fade toward. With reverseLayout the visual top is the
 * "backward" scroll direction.
 */
internal fun chatFadeShowTop(canScrollBackward: Boolean): Boolean = canScrollBackward

/**
 * Whether the BOTTOM fade should be drawn: only when there is content scrolled
 * off the bottom edge to fade toward. With reverseLayout the visual bottom is
 * the "forward" scroll direction.
 */
internal fun chatFadeShowBottom(canScrollForward: Boolean): Boolean = canScrollForward

/**
 * The color the chat content should dissolve INTO at the faded edges. Mirrors
 * the ThinkingShader bgColor logic (see ChatScreen) so the fade grades exactly
 * into the surface the chat is actually drawn on:
 *  - a solid chat background uses its own color
 *  - Default / Gradient backgrounds sit on the scaffold container color, which
 *    the caller passes in as [fallbackContainerColor]
 *    (== MaterialTheme.colorScheme.surfaceContainer).
 */
internal fun chatFadeTargetColor(
    chatBackground: ChatBackground,
    fallbackContainerColor: Color,
): Color = when (chatBackground) {
    is ChatBackground.SolidColor -> chatBackground.color
    else -> fallbackContainerColor
}

/**
 * Draw-layer overlay that softly fades the top and/or bottom edges of the
 * wrapped content into [targetColor]. Each edge is gated independently so a
 * fade only appears when there is scrollable content in that direction.
 *
 * Applies an offscreen [graphicsLayer] so the [BlendMode.DstIn] mask composes
 * against the node's own pixels. No-ops (returns the receiver unchanged) when
 * both edges are hidden, so there is zero overhead when the list isn't
 * scrollable.
 */
internal fun Modifier.chatFadingEdges(
    showTop: Boolean,
    showBottom: Boolean,
    fadeLength: Dp,
    targetColor: Color,
): Modifier {
    if (!showTop && !showBottom) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeLength.toPx().coerceAtMost(size.height / 2f)
            if (fadePx <= 0f) return@drawWithContent
            val transparent = targetColor.copy(alpha = 0.05f)
            if (showTop) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(transparent, targetColor),
                        startY = 0f,
                        endY = fadePx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (showBottom) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(targetColor, transparent),
                        startY = size.height - fadePx,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * Default fade length token for the chat edges. Uses the design-system spacing
 * scale (letta-mobile-awbf) so the fade reads as a deliberate, tokenized
 * polish detail rather than a magic number.
 */
internal val ChatFadeEdgeLength: Dp = LettaSpacing.xxl // 32.dp

/**
 * Composable convenience wrapper that boxes [content] with the fading-edge
 * overlay, deriving the per-edge gating from [listState] via [derivedStateOf]
 * so the draw layer reads stable booleans and is NOT recomposed on every
 * scroll pixel — only when an edge crosses the scrollable/unscrollable
 * boundary.
 */
@Composable
internal fun ChatFadingEdgesBox(
    listState: LazyListState,
    targetColor: Color,
    modifier: Modifier = Modifier,
    fadeLength: Dp = ChatFadeEdgeLength,
    // letta-mobile-58qlr.1: when true, the BOTTOM fade is suppressed regardless
    // of scroll state. The caller sets this while pinned to the newest edge so
    // a just-sent user prompt (or the live streaming bubble) at the bottom is
    // never dimmed by the fade. The transient typing/streaming slot can make
    // canScrollForward momentarily true even when the user is effectively at
    // the bottom; this guard keeps their fresh prompt fully visible.
    suppressBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val showTop by remember(listState) {
        derivedStateOf { chatFadeShowTop(listState.canScrollBackward) }
    }
    val showBottom by remember(listState, suppressBottom) {
        derivedStateOf { !suppressBottom && chatFadeShowBottom(listState.canScrollForward) }
    }
    // Touch density so the unit-conversion in the modifier is well-defined even
    // if this composable is ever previewed without a provided density.
    LocalDensity.current
    Box(
        modifier = modifier.chatFadingEdges(
            showTop = showTop,
            showBottom = showBottom,
            fadeLength = fadeLength,
            targetColor = targetColor,
        ),
    ) {
        content()
    }
}
