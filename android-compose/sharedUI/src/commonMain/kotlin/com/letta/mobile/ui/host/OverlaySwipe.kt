package com.letta.mobile.ui.host

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * A snackbar a touch user can flick away: dragged sideways it follows the finger and fades, and let go
 * past [LettaSurfaceSizes.snackbarSwipeThreshold] it slides off and [onDismiss] runs (the same as its
 * time running out, so an undo commits); short of it, it springs back. Under reduced motion it leaves
 * or returns at once. Pointer hosts get no drag (they have the close control and the timer).
 *
 * Every host also gets the accessibility "dismiss" action, the swipe's equivalent for a screen reader.
 */
@Composable
internal fun Modifier.swipeAwayOnTouch(enabled: Boolean, onDismiss: () -> Unit): Modifier {
    val dismiss = rememberUpdatedState(onDismiss)
    val described = Modifier.semantics { dismiss { dismiss.value(); true } }
    if (!enabled) return this.then(described)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val thresholdPx = with(LocalDensity.current) { LettaSurfaceSizes.snackbarSwipeThreshold.toPx() }
    return this
        .then(described)
        .graphicsLayer {
            translationX = offset.value
            alpha = (1f - abs(offset.value) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f)
        }
        .pointerInput(thresholdPx, reduced) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        val away = abs(offset.value) >= thresholdPx
                        val target = if (away) sign(offset.value) * size.width else 0f
                        val spec = if (reduced) snap() else tween<Float>(LettaMotionTokens.EXIT_MILLIS)
                        offset.animateTo(target, spec)
                        if (away) dismiss.value()
                    }
                },
                onDragCancel = { scope.launch { offset.snapTo(0f) } },
            ) { change, dragAmount ->
                change.consume()
                scope.launch { offset.snapTo(offset.value + dragAmount) }
            }
        }
}

/**
 * How an overlay's message is announced when it appears or changes: an error banner interrupts
 * (assertive), anything else waits its turn (polite).
 */
internal fun Modifier.overlayAnnouncement(urgent: Boolean): Modifier =
    semantics { liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite }
