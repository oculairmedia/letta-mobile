package com.letta.mobile.ui.context

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.towards
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * [target] as the meter draws it. When the numbers change (the streamed
 * estimate giving way to the real breakdown, a settled turn, a compaction) the meter travels to
 * them from what it showed instead of snapping; with reduced motion it takes them as they come.
 */
@Composable
internal fun animatedContextUsage(target: ContextWindowUsage?): ContextWindowUsage? {
    target ?: return null
    val reducedMotion = LocalReducedMotion.current
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target, reducedMotion) {
        if (target == to) return@LaunchedEffect
        // Start from where the meter is now, so a change mid-travel does not jump either.
        from = from.towards(to, progress.value)
        to = target
        if (reducedMotion) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(LettaMotionTokens.CONTENT_SIZE_MILLIS))
        }
    }
    return from.towards(to, progress.value)
}
