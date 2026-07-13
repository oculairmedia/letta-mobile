package com.letta.mobile.desktop.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.theme.customColors

/**
 * Gradient agent identity orbs, matching the Penpot "App Mockups v2" desktop
 * boards. Rail/sidebar avatars use the diagonal linear gradients; the chat
 * agent avatar uses the radial teal "sphere" with a soft highlight.
 */

/** Diagonal linear-gradient color pairs for the rail/sidebar agent orbs. */
private val AgentOrbGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFFF0A03C) to Color(0xFFE0457B),
    Color(0xFFE0457B) to Color(0xFF8E5CFF),
    Color(0xFF3FA0F0) to Color(0xFF3FE0C0),
    Color(0xFF7AD08F) to Color(0xFF3FA0A0),
    Color(0xFF8E7CFF) to Color(0xFF3F6EF0),
    Color(0xFF3FC0D0) to Color(0xFF3F90A0),
)

/** Brush for the agent orb at [index] (cycles through the palette). */
fun agentOrbBrush(index: Int): Brush {
    val (start, end) = AgentOrbGradients[((index % AgentOrbGradients.size) + AgentOrbGradients.size) % AgentOrbGradients.size]
    // Penpot gradient runs ~(0.2,0.1) -> (0.9,0.95); a default linear gradient
    // (top-left -> bottom-right over the full bounds) approximates it closely.
    return Brush.linearGradient(colors = listOf(start, end))
}

/** A rounded-square gradient agent orb (rail / sidebar avatar). */
@Composable
fun AgentOrb(
    index: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 7.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        // clip BEFORE clickable so the hover/press indication follows the orb's
        // rounded shape instead of a rectangle. onClick is applied here (not by
        // the caller's modifier) so it sits inside the clip.
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(agentOrbBrush(index))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content?.invoke()
    }
}

/**
 * The radial teal "sphere" used as the assistant avatar in the chat thread:
 * #7CF0DE -> #00BFA5 (0.55) -> #00897B with a small white highlight top-left.
 */
@Composable
fun AgentSphere(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val sphere = Brush.radialGradient(
        0.0f to Color(0xFF7CF0DE),
        0.55f to Color(0xFF00BFA5),
        1.0f to Color(0xFF00897B),
    )
    // Soft highlight near the top-left for a glossy sphere look.
    val highlight = Brush.radialGradient(
        0.0f to Color(0x80FFFFFF),
        0.45f to Color(0x00FFFFFF),
        center = Offset(sizePx * 0.32f, sizePx * 0.28f),
        radius = sizePx * 0.7f,
    )
    Box(
        modifier = modifier
            .size(size)
            .background(sphere, androidx.compose.foundation.shape.CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(highlight, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/**
 * Activity state for the agent orb, matching the animated "working / firing /
 * error" orb states in the Penpot "App Mockups v2" desktop boards (Background
 * tasks panel, schedule heartbeat, tool error). Each non-idle state radiates
 * concentric, breathing rings in a state color around the identity sphere.
 */
enum class AgentActivity { Idle, Working, Firing, Error }

/** State color for the radiating activity rings (per the desktop mockups). */
@Composable
private fun AgentActivity.ringColor(): Color = when (this) {
    AgentActivity.Idle -> Color.Transparent
    AgentActivity.Working -> MaterialTheme.customColors.warningTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary
    AgentActivity.Firing -> MaterialTheme.customColors.successColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    AgentActivity.Error -> MaterialTheme.customColors.errorTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.error
}

/**
 * The agent identity sphere wrapped in an animated activity halo. When
 * [activity] is not [AgentActivity.Idle], two staggered rings expand outward
 * from the sphere and fade — the "heartbeat" pulse the mockups use to signal
 * that the agent (or a background task) is alive and working.
 *
 * [size] is the full footprint; the inner sphere is drawn at ~62% so the rings
 * have room to breathe without being clipped.
 */
@Composable
fun AgentActivityOrb(
    size: Dp,
    activity: AgentActivity,
    modifier: Modifier = Modifier,
) {
    val sphereSize = size * SphereFraction
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (activity != AgentActivity.Idle) {
            val color = activity.ringColor()
            val transition = rememberInfiniteTransition(label = "agentActivity")
            val ringProgress = (0 until ActivityRingCount).map { ring ->
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = ActivityRingPeriodMs, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                        // Stagger the rings evenly across the period so the pulse is continuous.
                        initialStartOffset = StartOffset(ring * ActivityRingPeriodMs / ActivityRingCount),
                    ),
                    label = "activityRing$ring",
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxRadius = this.size.minDimension / 2f
                val innerRadius = maxRadius * SphereFraction
                val stroke = maxRadius * 0.08f
                ringProgress.forEach { progress ->
                    val p = progress.value
                    val radius = innerRadius + (maxRadius - innerRadius) * p
                    drawCircle(
                        color = color.copy(alpha = (1f - p) * 0.55f),
                        radius = radius,
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
        AgentSphere(size = sphereSize)
    }
}

private const val SphereFraction = 0.62f
private const val ActivityRingCount = 2
private const val ActivityRingPeriodMs = 1600
