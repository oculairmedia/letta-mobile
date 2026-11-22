package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.a11y.politeLiveRegion
import com.letta.mobile.ui.components.gesture.SwipeAwayExit
import com.letta.mobile.ui.components.gesture.SwipeAwayRelease
import com.letta.mobile.ui.components.gesture.SwipeAwaySpec
import com.letta.mobile.ui.components.gesture.SwipeAwayThreshold
import com.letta.mobile.ui.components.gesture.swipeAway
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes

/**
 * A snackbar a touch user can flick away ([swipeAway]): dragged sideways it follows the finger and
 * fades, and let go past [LettaSurfaceSizes.snackbarSwipeThreshold] it slides off and [onDismiss] runs
 * (the same as its time running out, so an undo commits); short of it, it springs back. Under reduced
 * motion it leaves or returns at once. Pointer hosts get no drag (they have the close control and the
 * timer), but every host gets the accessibility "dismiss" action.
 */
@Composable
internal fun Modifier.swipeAwayOnTouch(enabled: Boolean, onDismiss: () -> Unit): Modifier =
    swipeAway(SnackbarSwipe, enabled, onDismiss)

private val SnackbarSwipe = SwipeAwaySpec(
    threshold = SwipeAwayThreshold.Distance(LettaSurfaceSizes.snackbarSwipeThreshold),
    release = SwipeAwayRelease(
        SwipeAwayExit.OffEdge,
        exitMillis = LettaMotionTokens.EXIT_MILLIS,
        settleMillis = LettaMotionTokens.EXIT_MILLIS,
    ),
)

/**
 * How an overlay's message is announced when it appears or changes: an error banner interrupts
 * (assertive), anything else waits its turn (polite).
 */
internal fun Modifier.overlayAnnouncement(urgent: Boolean): Modifier =
    if (urgent) semantics { liveRegion = LiveRegionMode.Assertive } else politeLiveRegion()
