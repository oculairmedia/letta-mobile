package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import com.letta.mobile.ui.theme.ChatComposerDimens
import kotlin.math.abs

/**
 * letta-mobile-bglj6.1: swipe up on the full-screen prompt card to open the canvas, re-owned
 * from Android's SwipeUpToCanvasModifier (letta-mobile-swup) so both platforms share it.
 *
 * The card is the gesture surface: touch anywhere on it, drag up, release. The gesture commits
 * when EITHER the drag passes [ChatComposerDimens.swipeDistanceThreshold] OR the upward velocity
 * at release passes [ChatComposerDimens.swipeVelocityThresholdPerSecond]; short of both it is
 * swallowed and the card stays put. Horizontal drags are not consumed. A cancelled stream (a
 * release Compose already consumed) never commits (letta-mobile-erx7m).
 *
 * The caller decides when it is offered: [enabled] is false while the keyboard is open (so the
 * gesture does not fight the IME) and while a run streams (so a swipe mid-turn does not abandon
 * it).
 */
internal fun Modifier.swipeUpToCanvas(
    enabled: Boolean,
    onTrigger: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    val haptic = LocalHapticFeedback.current
    // Keyed on Unit: a fresh lambda must not restart the detector and drop an in-flight drag.
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    pointerInput(Unit) {
        runSwipeUpGesture(
            onThresholdCrossed = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
            onTrigger = { currentOnTrigger() },
        )
    }
}

/** Upward travel must beat sideways travel by this ratio to count as a swipe up. */
private const val VerticalDominanceRatio = 1.6f

/** Sideways travel this many times the vertical abandons the gesture to the list above. */
private const val HorizontalAbandonRatio = 2f

internal fun isVerticalDominant(totalDx: Float, totalDy: Float): Boolean =
    abs(totalDy) > abs(totalDx) * VerticalDominanceRatio

private fun isDragAbandoned(totalDx: Float, totalDy: Float): Boolean =
    !isVerticalDominant(totalDx, totalDy) && abs(totalDx) > abs(totalDy) * HorizontalAbandonRatio

/** What the end of one swipe-up gesture amounted to. */
internal enum class SwipeUpOutcome {
    /** Released past the distance or velocity threshold: open the canvas. */
    Committed,

    /** Released short of both thresholds. */
    Released,

    /** The stream was cancelled, or another node claimed the release. */
    Cancelled,
}

/** A consumed release is a synthesised cancel (or claimed elsewhere) and never commits. */
internal fun swipeUpReleaseOutcome(releaseConsumed: Boolean, thresholdsMet: Boolean): SwipeUpOutcome = when {
    releaseConsumed -> SwipeUpOutcome.Cancelled
    thresholdsMet -> SwipeUpOutcome.Committed
    else -> SwipeUpOutcome.Released
}

private class SwipeUpThresholds(val distancePx: Float, val velocityPx: Float, val slopPx: Float)

/** Accumulated state of one gesture, from its DOWN to its end. */
private class SwipeUpGesture(down: PointerInputChange, private val thresholds: SwipeUpThresholds) {
    private val velocityTracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    private var totalDx = 0f
    private var totalDy = 0f
    private var thresholdCrossed = false
    private val distanceUp get() = -totalDy

    fun track(change: PointerInputChange) {
        val delta = change.positionChange()
        totalDx += delta.x
        totalDy += delta.y
        velocityTracker.addPosition(change.uptimeMillis, change.position)
    }

    fun isAbandoned(): Boolean = isDragAbandoned(totalDx, totalDy)

    /** Consumes the move once upward intent is clear; true when the distance threshold is first crossed. */
    fun claimIfUpward(change: PointerInputChange): Boolean {
        if (distanceUp <= thresholds.slopPx || !isVerticalDominant(totalDx, totalDy)) return false
        change.consume()
        if (thresholdCrossed || distanceUp < thresholds.distancePx) return false
        thresholdCrossed = true
        return true
    }

    fun release(change: PointerInputChange): SwipeUpOutcome =
        swipeUpReleaseOutcome(releaseConsumed = change.isConsumed, thresholdsMet = thresholdsMet())

    private fun thresholdsMet(): Boolean {
        if (thresholdCrossed || distanceUp >= thresholds.distancePx) return true
        val velocityUp = -velocityTracker.calculateVelocity().y // pointer Y grows downward
        return distanceUp > 0f && isVerticalDominant(totalDx, totalDy) && velocityUp >= thresholds.velocityPx
    }
}

private suspend fun PointerInputScope.runSwipeUpGesture(
    onThresholdCrossed: () -> Unit,
    onTrigger: () -> Unit,
) {
    val thresholds = SwipeUpThresholds(
        distancePx = ChatComposerDimens.swipeDistanceThreshold.toPx(),
        velocityPx = ChatComposerDimens.swipeVelocityThresholdPerSecond.toPx(),
        slopPx = ChatComposerDimens.swipeSlop.toPx(),
    )
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val gesture = SwipeUpGesture(down, thresholds)
        if (trackSwipeUp(gesture, onThresholdCrossed) == SwipeUpOutcome.Committed) onTrigger()
    }
}

/** Runs one gesture from its DOWN to its end and says how it ended. */
private suspend fun AwaitPointerEventScope.trackSwipeUp(
    gesture: SwipeUpGesture,
    onThresholdCrossed: () -> Unit,
): SwipeUpOutcome {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull()
            ?: return SwipeUpOutcome.Cancelled
        if (!change.pressed) return gesture.release(change)
        if (change.isConsumed) return SwipeUpOutcome.Cancelled
        gesture.track(change)
        if (gesture.isAbandoned()) return SwipeUpOutcome.Released
        if (gesture.claimIfUpward(change)) onThresholdCrossed()
    }
}
