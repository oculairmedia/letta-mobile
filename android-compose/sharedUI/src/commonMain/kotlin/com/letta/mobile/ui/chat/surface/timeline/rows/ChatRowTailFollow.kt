package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/**
 * A bounded viewport's scroll state that follows its tail while [live]: as content grows at the
 * bottom (streaming output, steps added to a running turn) it stays scrolled to the end, unless
 * the person scrolled up, and it follows again once they scroll back down. Settled, it is an
 * ordinary scroll state at the top.
 */
@Composable
internal fun rememberTailFollowingScroll(live: Boolean): ScrollState {
    val state = rememberScrollState()
    LaunchedEffect(state, live) {
        if (!live) return@LaunchedEffect
        state.scrollTo(state.maxValue)
        var previousEnd = state.maxValue
        snapshotFlow { state.maxValue }.collect { end ->
            // Followed when the reader was at the old end; a reader who scrolled up keeps their place.
            if (state.value >= previousEnd - TAIL_SLACK_PX) state.scrollTo(end)
            previousEnd = end
        }
    }
    return state
}

/** How far above the end still counts as "at the end": a line's worth, so a nudge does not stop following. */
private const val TAIL_SLACK_PX = 24
