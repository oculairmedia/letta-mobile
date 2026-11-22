package com.letta.mobile.ui.components.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * An item a touch user can flick away. Dragged along the [SwipeAwaySpec.axis] it follows the finger
 * and fades by [SwipeAwaySpec.fade]; let go past [SwipeAwaySpec.threshold] it leaves as
 * [SwipeAwaySpec.release] says and [onDismiss] runs, short of it it springs back. Under reduced motion
 * (`LocalLettaReducedMotion`) it leaves or returns at once, and still dismisses.
 *
 * Every item gets the accessibility "dismiss" action, the swipe's equivalent for a screen reader, even
 * where the drag is off ([enabled] false: a pointer host that has a close control instead).
 */
@Composable
fun Modifier.swipeAway(spec: SwipeAwaySpec, enabled: Boolean = true, onDismiss: () -> Unit): Modifier {
    val dismiss = rememberUpdatedState(onDismiss)
    val described = this.semantics { dismiss { dismiss.value(); true } }
    if (!enabled) return described
    val drag = SwipeAwayDrag(
        offset = remember { Animatable(0f) },
        scope = rememberCoroutineScope(),
        spec = rememberUpdatedState(spec),
        reduced = rememberUpdatedState(LocalLettaReducedMotion.current),
    )
    return described
        .graphicsLayer {
            val extent = spec.axis.extent(size)
            val travelled = (abs(drag.offset.value) / extent.coerceAtLeast(1f)).coerceIn(0f, 1f)
            if (spec.axis == SwipeAwayAxis.Horizontal) translationX = drag.offset.value else translationY = drag.offset.value
            alpha = 1f - travelled * spec.fade
        }
        .pointerInput(spec.axis) { detectSwipeAway(spec.axis, drag) { dismiss.value() } }
}

/** The drag's live state: where the item is, the scope its motion runs in, and the latest spec and preference. */
private class SwipeAwayDrag(
    val offset: Animatable<Float, AnimationVector1D>,
    val scope: CoroutineScope,
    val spec: State<SwipeAwaySpec>,
    val reduced: State<Boolean>,
)

private suspend fun PointerInputScope.detectSwipeAway(axis: SwipeAwayAxis, drag: SwipeAwayDrag, onDismiss: () -> Unit) {
    val onEnd: () -> Unit = { drag.scope.launch { drag.release(axis.extent(size), thresholdPx(drag, axis), onDismiss) } }
    val onCancel: () -> Unit = { drag.scope.launch { drag.offset.animateTo(0f, drag.motion(drag.spec.value.release.settleMillis)) } }
    val onDrag: (Float) -> Unit = { amount -> drag.scope.launch { drag.offset.snapTo(drag.offset.value + amount) } }
    when (axis) {
        SwipeAwayAxis.Horizontal -> detectHorizontalDragGestures(onDragEnd = onEnd, onDragCancel = onCancel) { change, amount ->
            change.consume()
            onDrag(amount)
        }
        SwipeAwayAxis.Vertical -> detectVerticalDragGestures(onDragEnd = onEnd, onDragCancel = onCancel) { change, amount ->
            change.consume()
            onDrag(amount)
        }
    }
}

private fun PointerInputScope.thresholdPx(drag: SwipeAwayDrag, axis: SwipeAwayAxis): Float =
    drag.spec.value.threshold.px(this, axis.extent(size))

/** Past the threshold the item leaves (off the edge, or in place) and [onDismiss] runs; short of it, it springs back. */
private suspend fun SwipeAwayDrag.release(extentPx: Float, thresholdPx: Float, onDismiss: () -> Unit) {
    val release = spec.value.release
    if (abs(offset.value) < thresholdPx) {
        offset.animateTo(0f, motion(release.settleMillis))
        return
    }
    if (release.exit == SwipeAwayExit.OffEdge) offset.animateTo(sign(offset.value) * extentPx, motion(release.exitMillis))
    onDismiss()
}

private fun SwipeAwayDrag.motion(millis: Int): AnimationSpec<Float> = if (reduced.value) snap() else tween(millis)

private fun SwipeAwayAxis.extent(size: Size): Float = if (this == SwipeAwayAxis.Horizontal) size.width else size.height

private fun SwipeAwayAxis.extent(size: IntSize): Float = (if (this == SwipeAwayAxis.Horizontal) size.width else size.height).toFloat()
