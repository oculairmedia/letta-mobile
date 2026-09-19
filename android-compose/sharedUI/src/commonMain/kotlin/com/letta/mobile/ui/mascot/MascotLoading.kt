package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A wait, shown as the agent: its live mascot inside an orbiting ring. Anywhere the product has to
 * make the user wait on an agent - a conversation opening, older history paging in, the editor
 * loading - this replaces the anonymous spinner, so the wait reads as the agent getting ready
 * rather than the app stalling. The character is drawn as a still; the ring is what says "loading".
 *
 * Without an agent, or without a renderer for it, [fallback] draws instead - the plain spinner by
 * default - so a call site never has to branch.
 */
@Composable
fun MascotLoading(
    agentId: String?,
    modifier: Modifier = Modifier,
    size: Dp = MascotLoadingSize,
    fallback: @Composable () -> Unit = { CircularProgressIndicator(modifier = Modifier.size(size)) },
) {
    if (agentId == null || !mascotAvailable(agentId)) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }
    Box(modifier.size(size + MascotLoadingRingInset * 2), contentAlignment = Alignment.Center) {
        LoadingOrbit(diameter = size + MascotLoadingRingInset * 2)
        // A still, not a live surface: this sits in list rows that come and go with the paging
        // state, and every live tile is one more native render of the scene per frame. The ring
        // carries the motion; the character is the agent, paused mid-pose.
        MascotAvatar(
            agentId = agentId,
            size = size,
            cornerRadius = size / 2,
            live = false,
            modifier = Modifier.padding(MascotLoadingRingInset),
            fallback = fallback,
        )
    }
}

/**
 * The orbiting comet the rail uses for "thinking": a sweep-gradient tail circling a faint static
 * track. Continuous motion with direction reads as work in progress.
 */
@Composable
private fun LoadingOrbit(diameter: Dp) {
    val transition = rememberInfiniteTransition(label = "mascotLoading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
        label = "mascotLoadingOrbit",
    )
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(diameter)) {
        val strokeWidth = LettaDimens.Space.hair.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        drawCircle(color = primary.copy(alpha = 0.18f), radius = radius, style = Stroke(width = strokeWidth))
        rotate(angle) {
            drawCircle(
                brush = Brush.sweepGradient(0.0f to Color.Transparent, 0.35f to Color.Transparent, 1.0f to primary),
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawCircle(color = primary, radius = strokeWidth * 0.9f, center = Offset(center.x + radius, center.y))
        }
    }
}

/** The mascot's tile inside the ring; the ring adds [MascotLoadingRingInset] all round. */
val MascotLoadingSize: Dp = LettaDimens.Orb.lg
private val MascotLoadingRingInset = LettaDimens.Space.xs
