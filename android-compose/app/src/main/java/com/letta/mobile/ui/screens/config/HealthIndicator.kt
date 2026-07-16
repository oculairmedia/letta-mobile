package com.letta.mobile.ui.screens.config

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.health.ServerHealthState
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * letta-mobile-qmxn: status dot for a backend row.
 *
 *   - ONLINE  → solid green
 *   - OFFLINE → solid red
 *   - PROBING → muted outline (probe in flight)
 *   - UNKNOWN → muted outline (no probe yet, or unreachable from
 *     the current scope)
 */
/**
 * Single source of truth for the health-dot palette. Pulled out of the
 * composable so the colors can be tuned (or themed) in one place rather
 * than scattered as inline literals.
 *
 * `online` stays as a fixed green because Material 3's color scheme has
 * no semantic "success" token; `offline` uses `colorScheme.error` so it
 * tracks light/dark theming correctly.
 */
private object HealthDotColors {
    val online: Color = Color(0xFF34C759)

    val offline: Color
        @Composable get() = MaterialTheme.colorScheme.error

    val unknown: Color
        @Composable get() = MaterialTheme.colorScheme.outlineVariant
}

@Composable
fun HealthDot(
    health: ServerHealthState,
    modifier: Modifier = Modifier,
) {
    val color = when (health) {
        ServerHealthState.ONLINE -> HealthDotColors.online
        ServerHealthState.OFFLINE -> HealthDotColors.offline
        ServerHealthState.PROBING, ServerHealthState.UNKNOWN -> HealthDotColors.unknown
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * letta-mobile-qmxn: container that wraps a backend row and runs the
 * shake+flash refusal animation whenever [refusalTrigger] increments.
 *
 * Hoisting the trigger as an Int (rather than a Boolean) means the
 * animation re-fires on every tap-on-dead, even if [refusalTrigger]
 * happens to land on a value that was already used — Compose only
 * restarts a `LaunchedEffect` when its key actually changes, so a
 * monotonically-increasing counter is the safest signal.
 */
@Composable
fun HealthRowShell(
    baseContainerColor: Color,
    contentColor: Color,
    refusalTrigger: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val shakeOffsetDp = remember { Animatable(0f) }
    val flashFraction = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(refusalTrigger) {
        if (refusalTrigger == 0) return@LaunchedEffect
        coroutineScope {
            launch {
                flashFraction.snapTo(0f)
                flashFraction.animateTo(1f, tween(80, easing = LinearEasing))
                flashFraction.animateTo(0f, tween(280, easing = LinearEasing))
            }
            launch {
                shakeOffsetDp.snapTo(0f)
                // 3 round trips, ±10dp, fast — about 240ms total.
                repeat(3) {
                    shakeOffsetDp.animateTo(10f, tween(40, easing = LinearEasing))
                    shakeOffsetDp.animateTo(-10f, tween(40, easing = LinearEasing))
                }
                shakeOffsetDp.animateTo(0f, tween(40, easing = LinearEasing))
            }
        }
    }

    val shakeOffsetPx = with(density) { shakeOffsetDp.value.dp.roundToPx() }
    val animatedColor = lerp(baseContainerColor, errorContainer, flashFraction.value)

    Surface(
        modifier = modifier.offset { IntOffset(shakeOffsetPx, 0) },
        shape = RoundedCornerShape(12.dp),
        color = animatedColor,
        contentColor = contentColor,
    ) {
        content()
    }
}
