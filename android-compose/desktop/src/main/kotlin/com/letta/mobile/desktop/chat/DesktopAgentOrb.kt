package com.letta.mobile.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    content: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(agentOrbBrush(index), RoundedCornerShape(cornerRadius)),
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
