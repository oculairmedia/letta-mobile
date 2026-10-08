package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.letta.mobile.ui.theme.ChatTimelineDimens

/**
 * letta-mobile-bglj6.1: how far from the newest content the reader must be before the
 * scroll-to-latest button shows ([showPx]), and how close they must come back before it hides
 * again ([hidePx]). The gap between the two is the hysteresis that keeps a reader resting near
 * the threshold from flickering it; the floor keeps a nudge of the list from raising it at all.
 */
@Immutable
internal data class ScrollToLatestThresholds(val showPx: Float, val hidePx: Float) {
    companion object {
        /** Shows at [SHOW_VIEWPORT_FRACTION] of the viewport, never under [minShowPx]. */
        const val SHOW_VIEWPORT_FRACTION: Float = 0.4f

        /** Hides again at this share of the show distance. */
        const val HIDE_FRACTION: Float = 1f / 3f

        fun of(viewportPx: Int, minShowPx: Float): ScrollToLatestThresholds {
            val show = maxOf(viewportPx * SHOW_VIEWPORT_FRACTION, minShowPx)
            return ScrollToLatestThresholds(showPx = show, hidePx = show * HIDE_FRACTION)
        }
    }
}

/**
 * The button's next visibility. Only a reader who is not following the newest edge ([eligible])
 * is offered it; then it shows once [distancePx] from the newest content reaches the show
 * threshold, and once shown it stays until the reader is back within the hide threshold.
 */
internal fun nextScrollToLatestVisible(
    visible: Boolean,
    eligible: Boolean,
    distancePx: Float,
    thresholds: ScrollToLatestThresholds,
): Boolean = when {
    !eligible -> false
    visible -> distancePx > thresholds.hidePx
    else -> distancePx >= thresholds.showPx
}

/**
 * Whether the list offers scroll-to-latest now, by [nextScrollToLatestVisible] over the list's
 * distance from its newest edge. [newerContentPending] is true when rows newer than the list's own
 * newest one exist but are not resident (a window anchored on a search target): the newest edge is
 * then not the conversation's, and the reader is always far from it.
 */
@Composable
internal fun rememberScrollToLatestVisible(
    listState: LazyListState,
    eligible: Boolean,
    newerContentPending: Boolean = false,
): Boolean {
    val minShowPx = with(LocalDensity.current) { ChatTimelineDimens.scrollToLatestMinShowDistance.toPx() }
    var visible by remember(listState) { mutableStateOf(false) }
    val isEligible by rememberUpdatedState(eligible)
    val pending by rememberUpdatedState(newerContentPending)
    LaunchedEffect(listState, minShowPx) {
        snapshotFlow {
            ScrollToLatestProbe(
                eligible = isEligible,
                distancePx = if (pending) Float.POSITIVE_INFINITY else listState.distanceToNewestPx(),
                thresholds = ScrollToLatestThresholds.of(listState.layoutInfo.viewportSize.height, minShowPx),
            )
        }.collect { probe -> visible = nextScrollToLatestVisible(visible, probe.eligible, probe.distancePx, probe.thresholds) }
    }
    return visible
}

private data class ScrollToLatestProbe(
    val eligible: Boolean,
    val distancePx: Float,
    val thresholds: ScrollToLatestThresholds,
)
