package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.haptics.HapticEffects
import kotlin.math.abs

/**
 * Swipe-up gesture modifier for the chat composer card.
 *
 * The card is the gesture surface — touch anywhere on it, drag up, release.
 * The gesture commits when EITHER the drag distance exceeds
 * [SwipeUpToCanvasDistanceThresholdDp] OR the upward velocity at release
 * exceeds [SwipeUpToCanvasVelocityThresholdDpPerSec]. If neither threshold
 * is met, the gesture is consumed silently and the card stays in chat.
 * Mirrors Miro's hybrid commit on its bottom drawer.
 *
 * Visual feedback (commit-vs-snap-back) is intentionally deferred to the
 * navigation transition: when the gesture commits, [onTrigger] fires and
 * the caller navigates to the canvas surface, which slides up + fades in
 * over the chat. Keeps this modifier a pure detector with no state — easy
 * to unit-test, no live transform during drag, no risk of fighting the
 * IME / text-field gestures below it.
 *
 * Skip the gesture entirely when [enabled] is false (caller controls when
 * it is offered — e.g. disable while the IME is open or the agent is
 * streaming) or when [onTrigger] is null (caller has no canvas entry
 * point — e.g. previews, tests, desktop builds).
 *
 * Horizontal drags are not consumed — the LazyColumn above continues to
 * scroll. The gesture only commits when the dominant direction of the
 * drag is upward.
 */
@Composable
fun Modifier.swipeUpToCanvas(
    enabled: Boolean,
    onTrigger: (() -> Unit)?,
): Modifier = composed {
    if (!enabled || onTrigger == null) return@composed this
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val onTriggerState = remember(onTrigger) { onTrigger }
    this.pointerInput(onTriggerState) {
        runSwipeUpToCanvasGesture(
            haptic = haptic,
            view = view,
            onTrigger = onTriggerState,
        )
    }
}

private val SwipeUpToCanvasDistanceThresholdDp = 32.dp
private val SwipeUpToCanvasVelocityThresholdDpPerSec = 800.dp
private val SwipeUpToCanvasSlopDp = 12.dp
private const val SwipeUpToCanvasVerticalDominanceRatio = 1.6f

/**
 * Detect the swipe-up gesture within a [PointerInputScope]. The scope gives
 * us access to density (for dp -> px conversion) and the awaitPointerEvent
 * machinery. The gesture loop:
 *  - wait for ACTION_DOWN
 *  - on each pointer event, accumulate dx/dy + velocity samples
 *  - consume the gesture once the upward intent is clear (vertical-dominant,
 *    past slop)
 *  - on release, commit if distance OR velocity threshold met; otherwise
 *    swallow the gesture and stay in chat
 *
 * `onTrigger` is invoked at most once per gesture.
 */
private suspend fun PointerInputScope.runSwipeUpToCanvasGesture(
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    view: android.view.View?,
    onTrigger: () -> Unit,
) {
    val distanceThresholdPx = SwipeUpToCanvasDistanceThresholdDp.toPx()
    val velocityThresholdPx = SwipeUpToCanvasVelocityThresholdDpPerSec.toPx()
    val slopPx = SwipeUpToCanvasSlopDp.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var totalDy = 0f
        var totalDx = 0f
        var thresholdCrossed = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change: PointerInputChange = event.changes.firstOrNull() ?: break

            if (!change.pressed) {
                // Release. Commit if either threshold met at this moment.
                val velocityY = velocityTracker.calculateVelocity().y
                val velocityUp = -velocityY // pointer Y grows downward
                val distanceUp = -totalDy
                val committed = thresholdCrossed ||
                    distanceUp >= distanceThresholdPx ||
                    velocityUp >= velocityThresholdPx
                if (committed) onTrigger()
                break
            }

            if (change.isConsumed) {
                // Another handler (LazyColumn, scroll) took the pointer — bail.
                break
            }

            val delta = change.positionChange()
            totalDy += delta.y
            totalDx += delta.x
            velocityTracker.addPosition(change.uptimeMillis, change.position)

            val upward = totalDy < 0f
            val distanceUp = -totalDy
            val verticalDominant =
                abs(totalDy) > abs(totalDx) * SwipeUpToCanvasVerticalDominanceRatio

            // Horizontal-dominant drag — abandon so the message list can scroll.
            if (!verticalDominant && abs(totalDx) > abs(totalDy) * 2f) {
                break
            }

            if (upward && verticalDominant && distanceUp > slopPx) {
                change.consume()
                if (!thresholdCrossed && distanceUp >= distanceThresholdPx) {
                    thresholdCrossed = true
                    HapticEffects.gestureThreshold(haptic, view)
                }
            }
        }
    }
}
