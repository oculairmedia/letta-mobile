package com.letta.mobile.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.lettaFadingEdges
import com.letta.mobile.ui.components.lettaHorizontalFadingEdges

/**
 * Softly dissolves the top and bottom of a scrolling list (the desktop chat message list and the
 * agent rail): the shared [lettaFadingEdges].
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
): Modifier = lettaFadingEdges(topFadeAlpha, bottomFadeAlpha, topFadeLength, bottomFadeLength)

/** The horizontal counterpart of [fadingEdges], for rows that scroll sideways: the shared [lettaHorizontalFadingEdges]. */
internal fun Modifier.horizontalFadingEdges(
    startFadeAlpha: Float,
    endFadeAlpha: Float,
    fadeLength: Dp,
): Modifier = lettaHorizontalFadingEdges(startFadeAlpha, endFadeAlpha, fadeLength)
