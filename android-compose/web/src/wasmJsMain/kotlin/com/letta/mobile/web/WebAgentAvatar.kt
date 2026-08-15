package com.letta.mobile.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val WebAgentGradients = listOf(
    Color(0xFFF0A03C) to Color(0xFFE0457B),
    Color(0xFFE0457B) to Color(0xFF8E5CFF),
    Color(0xFF3FA0F0) to Color(0xFF3FE0C0),
    Color(0xFF7AD08F) to Color(0xFF3FA0A0),
    Color(0xFF8E7CFF) to Color(0xFF3F6EF0),
    Color(0xFF3FC0D0) to Color(0xFF3F90A0),
)

@Composable
internal fun WebAgentAvatar(index: Int, size: Dp, modifier: Modifier = Modifier) {
    val colors = WebAgentGradients[index.mod(WebAgentGradients.size)]
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(colors.first, colors.second)), RoundedCornerShape(7.dp)),
    )
}

@Composable
internal fun WebAgentSphere(size: Dp, modifier: Modifier = Modifier) {
    val sphere = Brush.radialGradient(
        0f to Color(0xFF7CF0DE),
        0.55f to Color(0xFF00BFA5),
        1f to Color(0xFF00897B),
    )
    val highlight = Brush.radialGradient(
        0f to Color(0x80FFFFFF),
        0.45f to Color.Transparent,
        center = Offset(0f, 0f),
    )
    Box(modifier = modifier.size(size).background(sphere, CircleShape)) {
        Box(Modifier.fillMaxSize().background(highlight, CircleShape))
    }
}
