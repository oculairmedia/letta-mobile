package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import kotlin.coroutines.cancellation.CancellationException
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
 *
 * A cancelled stream (ACTION_CANCEL, window focus loss) never commits:
 * Compose delivers the cancel as a release whose change is already consumed
 * (letta-mobile-erx7m), and a consumed release is treated as an abandon.
 */
@Composable
fun Modifier.swipeUpToCanvas(
    enabled: Boolean,
    onTrigger: (() -> Unit)?,
): Modifier = composed {
    if (!enabled || onTrigger == null) return@composed this
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    // Keyed on Unit: a fresh lambda from the caller must not restart the
    // detector (and drop an in-flight drag) on every recomposition.
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    this.pointerInput(Unit) {
        runSwipeUpToCanvasGesture(
            haptic = haptic,
            view = view,
            onTrigger = { currentOnTrigger() },
        )
    }
}

private val SwipeUpToCanvasDistanceThresholdDp = 32.dp
private val SwipeUpToCanvasVelocityThresholdDpPerSec = 800.dp
private val SwipeUpToCanvasSlopDp = 12.dp
private const val SwipeUpToCanvasVerticalDominanceRatio = 1.6f

private fun isVerticalDominant(totalDx: Float, totalDy: Float): Boolean =
    abs(totalDy) > abs(totalDx) * SwipeUpToCanvasVerticalDominanceRatio

private fun isDragAbandoned(verticalDominant: Boolean, totalDx: Float, totalDy: Float): Boolean =
    !verticalDominant && abs(totalDx) > abs(totalDy) * 2f

/** Pixel thresholds for one pointer-input scope. */
private class SwipeUpThresholds(
    val distancePx: Float,
    val velocityPx: Float,
    val slopPx: Float,
)

/** What the end of one swipe-up gesture amounted to. */
internal enum class SwipeUpToCanvasOutcome {
    /** Released past the distance or velocity threshold: open the canvas. */
    Committed,

    /** Released short of both thresholds. */
    Released,

    /** The stream was cancelled, or another node claimed the release. */
    Cancelled,
}

/**
 * Decide a release. A consumed release is a cancel synthesised by Compose
 * (or a release another node claimed) and never commits, whatever the drag
 * had reached.
 */
internal fun swipeUpReleaseOutcome(releaseConsumed: Boolean, thresholdsMet: Boolean): SwipeUpToCanvasOutcome = when {
    releaseConsumed -> SwipeUpToCanvasOutcome.Cancelled
    thresholdsMet -> SwipeUpToCanvasOutcome.Committed
    else -> SwipeUpToCanvasOutcome.Released
}

/** Accumulated state of one gesture, from the DOWN to its end. */
private class SwipeUpGesture(
    down: PointerInputChange,
    private val thresholds: SwipeUpThresholds,
) {
    private val velocityTracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    private var totalDx = 0f
    private var totalDy = 0f
    var claimed = false
        private set
    var thresholdCrossed = false
        private set

    private val distanceUp get() = -totalDy

    fun track(change: PointerInputChange) {
        val delta = change.positionChange()
        totalDy += delta.y
        totalDx += delta.x
        velocityTracker.addPosition(change.uptimeMillis, change.position)
    }

    fun isAbandoned(): Boolean = isDragAbandoned(isVerticalDominant(totalDx, totalDy), totalDx, totalDy)

    /** Consume the move once upward intent is clear. True when the distance threshold is first crossed. */
    fun claimIfUpward(change: PointerInputChange): Boolean {
        val pastSlop = distanceUp > thresholds.slopPx && isVerticalDominant(totalDx, totalDy)
        if (!pastSlop) return false
        change.consume()
        if (!claimed) {
            claimed = true
            SwipeUpToCanvasDiagnostics.started()
        }
        if (thresholdCrossed || distanceUp < thresholds.distancePx) return false
        thresholdCrossed = true
        return true
    }

    fun release(change: PointerInputChange): SwipeUpToCanvasOutcome =
        swipeUpReleaseOutcome(releaseConsumed = change.isConsumed, thresholdsMet = thresholdsMet())

    private fun thresholdsMet(): Boolean {
        if (thresholdCrossed || distanceUp >= thresholds.distancePx) return true
        val velocityUp = -velocityTracker.calculateVelocity().y // pointer Y grows downward
        return distanceUp > 0f && isVerticalDominant(totalDx, totalDy) && velocityUp >= thresholds.velocityPx
    }
}

/**
 * Detect the swipe-up gesture within a [PointerInputScope]. The gesture loop:
 *  - wait for ACTION_DOWN
 *  - on each pointer event, accumulate dx/dy + velocity samples
 *  - consume the gesture once the upward intent is clear (vertical-dominant,
 *    past slop)
 *  - on release, commit if distance OR velocity threshold met; otherwise
 *    swallow the gesture and stay in chat; a cancelled stream never commits
 *
 * `onTrigger` is invoked at most once per gesture.
 */
private suspend fun PointerInputScope.runSwipeUpToCanvasGesture(
    haptic: HapticFeedback,
    view: android.view.View?,
    onTrigger: () -> Unit,
) {
    val thresholds = SwipeUpThresholds(
        distancePx = SwipeUpToCanvasDistanceThresholdDp.toPx(),
        velocityPx = SwipeUpToCanvasVelocityThresholdDpPerSec.toPx(),
        slopPx = SwipeUpToCanvasSlopDp.toPx(),
    )
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val gesture = SwipeUpGesture(down, thresholds)
        val end = try {
            trackSwipeUp(gesture) { HapticEffects.gestureThreshold(haptic, view) }
        } catch (cancelled: CancellationException) {
            // The detector left composition (navigation, enabled flipped) mid-gesture.
            if (gesture.claimed) SwipeUpToCanvasDiagnostics.ended("detached")
            throw cancelled
        }
        if (gesture.claimed || end == SwipeUpToCanvasOutcome.Committed) SwipeUpToCanvasDiagnostics.ended(end.name)
        if (end == SwipeUpToCanvasOutcome.Committed) onTrigger()
    }
}

/** Runs one gesture from its DOWN to its end and says how it ended. */
private suspend fun AwaitPointerEventScope.trackSwipeUp(
    gesture: SwipeUpGesture,
    onThresholdCrossed: () -> Unit,
): SwipeUpToCanvasOutcome {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull()
            ?: return SwipeUpToCanvasOutcome.Cancelled
        if (!change.pressed) return gesture.release(change)
        if (change.isConsumed) return SwipeUpToCanvasOutcome.Cancelled
        gesture.track(change)
        if (gesture.isAbandoned()) return SwipeUpToCanvasOutcome.Released
        if (gesture.claimIfUpward(change)) onThresholdCrossed()
    }
}
