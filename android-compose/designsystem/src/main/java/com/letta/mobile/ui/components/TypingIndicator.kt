package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.customColors

@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
) {
    val bubbleColor = MaterialTheme.customColors.agentBubbleBgColor

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bubbleColor,
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DotPulse(color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "Thinking\u2026",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DotPulse(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(modifier = Modifier.size(6.dp).alpha(0.38f), shape = CircleShape, color = color) {}
        Surface(modifier = Modifier.size(6.dp).alpha(0.62f), shape = CircleShape, color = color) {}
        Surface(modifier = Modifier.size(6.dp).alpha(0.90f), shape = CircleShape, color = color) {}
    }
}
