package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation

/**
 * Marks the first drawn frame that shows rows, for the open-latency trace. The modifier is built once
 * per presentation and reads [hasRows] through state, so a recomposition never changes it and so never
 * invalidates a draw; it is plain [Modifier] when the open is not traced.
 */
@Composable
internal fun rememberFirstFrameMarker(presentation: CanonicalTimelinePresentation, hasRows: Boolean): Modifier {
    val traced = presentation.isOpenTraced
    val current = rememberUpdatedState(hasRows)
    return remember(presentation, traced) {
        if (!traced) {
            Modifier
        } else {
            Modifier.drawWithContent {
                drawContent()
                presentation.traceFirstFrame(current.value)
            }
        }
    }
}

/**
 * Feeds the open-latency trace each list replacement and polls for its quiet period. Keyed on [rows],
 * a new object per presented list, so each replacement restarts the quiet period. Nothing is launched
 * unless a traced open exists.
 */
@Composable
internal fun TimelineOpenSettleEffect(presentation: CanonicalTimelinePresentation, rows: PagedRows) {
    if (presentation.isOpenTraced) LaunchedEffect(rows) { presentation.traceOpenUntilSettled() }
}
