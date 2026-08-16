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
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.theme.customColors

@Composable
internal fun WebAgentAvatar(index: Int, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.customColors.agentGradientColors
    val colorIndex = index.mod(colors.size / 2) * 2
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(colors[colorIndex], colors[colorIndex + 1])), RoundedCornerShape(7.dp)),
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
