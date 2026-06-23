package com.letta.mobile.desktop.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/** Coarse agent activity used to tint the ambient glow. */
internal enum class DesktopAmbientStatus { Idle, Running, Failed, Completed }

/**
 * Desktop port of the mobile chat's ambient agent-status glow: a soft radial
 * gradient anchored at the bottom-center that breathes while the agent works
 * (teal), flashes the error color on failure, and settles after completion.
 *
 * The mobile app draws this with an AGSL `RuntimeShader`; Compose for Desktop
 * has no `android.graphics.RuntimeShader`, so this mirrors the mobile non-shader
 * fallback (a layered `Brush.radialGradient`), which produces the same look.
 */
@Composable
internal fun DesktopAmbientChatBackground(
    status: DesktopAmbientStatus,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val targetColor = remember(status, colorScheme) {
        when (status) {
            DesktopAmbientStatus.Idle -> Color.Transparent
            DesktopAmbientStatus.Running -> colorScheme.tertiary
            DesktopAmbientStatus.Failed -> colorScheme.error
            DesktopAmbientStatus.Completed -> colorScheme.secondary
        }
    }
    val tint by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "ambientTint",
    )
    val transition = rememberInfiniteTransition(label = "ambientGlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambientPhase",
    )

    Box(modifier = modifier) {
        if (tint.alpha > HiddenAlpha) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val breath = 0.5f + 0.5f * sin(phase)
                val radius = size.maxDimension * (0.52f + 0.12f * breath)
                val center = Offset(size.width * 0.5f, size.height * 0.92f)
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to tint.copy(alpha = tint.alpha * 0.18f),
                            0.52f to tint.copy(alpha = tint.alpha * 0.08f),
                            1.00f to tint.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = radius,
                    ),
                )
            }
        }
        content()
    }
}

private const val HiddenAlpha = 0.001f
