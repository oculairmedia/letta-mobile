package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameMillis
import com.letta.mobile.ui.theme.ChatMotionTokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * letta-mobile-cc25e: runs one flight on the frame clock (so a test's clock drives it).
 *
 * 1. Hold at the prompt until the row claims the flight, at most
 *    [ChatMotionTokens.SendFlight.TARGET_WAIT_MILLIS]; otherwise fade out where it stands.
 * 2. Open the row's slot (eased insert) while the ghost travels to it on the eased path.
 * 3. Cross-fade: the ghost out, the real row in.
 */
internal suspend fun SendFlight.fly() {
    if (!awaitTarget(ChatMotionTokens.SendFlight.TARGET_WAIT_MILLIS)) {
        phase = SendFlightPhase.Abandoned
        tweenTo(ChatMotionTokens.SendFlight.ABANDON_FADE_MILLIS) { ghostAlpha = 1f - it }
        return
    }
    phase = SendFlightPhase.Flying
    coroutineScope {
        launch {
            tweenTo(ChatMotionTokens.SendFlight.INSERT_MILLIS, ChatMotionTokens.SendFlight.insertEasing) { insert = it }
        }
        tweenTo(ChatMotionTokens.SendFlight.FLIGHT_MILLIS, ChatMotionTokens.SendFlight.flightEasing) { progress = it }
    }
    phase = SendFlightPhase.HandingOff
    tweenTo(ChatMotionTokens.SendFlight.HANDOFF_MILLIS) {
        rowAlpha = it
        ghostAlpha = 1f - it
    }
}

/** True once a row has claimed the flight; false after [timeoutMillis] of frames without one. */
private suspend fun SendFlight.awaitTarget(timeoutMillis: Int): Boolean {
    val start = withFrameMillis { it }
    while (target == null) {
        val now = withFrameMillis { it }
        if (now - start >= timeoutMillis) return false
    }
    return true
}

private suspend fun tweenTo(
    durationMillis: Int,
    easing: Easing = LinearEasing,
    onValue: (Float) -> Unit,
) {
    animate(0f, 1f, animationSpec = tween(durationMillis, easing = easing)) { value, _ -> onValue(value) }
}
