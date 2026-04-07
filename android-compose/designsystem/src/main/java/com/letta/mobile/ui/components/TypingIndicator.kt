package com.letta.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.customColors

@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
) {
    val bubbleColor = MaterialTheme.customColors.agentBubbleBgColor
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(MessageBubbleShape(radius = 16.dp, isFromUser = false, groupPosition = GroupPosition.None))
            .background(bubbleColor)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val transition = rememberInfiniteTransition(label = "typing")
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val offsetY by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = -4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 400, delayMillis = index * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .offset(y = offsetY.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.6f))
                )
            }
        }
    }
}
