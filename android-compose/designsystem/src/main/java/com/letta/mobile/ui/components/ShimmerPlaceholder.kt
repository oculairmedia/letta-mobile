package com.letta.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import com.letta.mobile.ui.motion.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens

@Composable
fun shimmerColor(): Color {
    val reducedMotion = rememberReducedMotionEnabled()
    return if (reducedMotion) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = ChatMotionTokens.RUNNING_CUE_STATIC_ALPHA)
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val alpha by transition.animateFloat(
            initialValue = ChatMotionTokens.RUNNING_CUE_MIN_ALPHA,
            targetValue = ChatMotionTokens.RUNNING_CUE_MAX_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(ChatMotionTokens.RUNNING_CUE_DURATION_MILLIS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shimmerAlpha",
        )
        MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = LettaDimens.Space.lg,
    widthFraction: Float = 1f,
    cornerRadius: Dp = LettaDimens.Radius.sm,
) {
    val color = shimmerColor()
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(color)
    )
}

@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LettaDimens.Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ShimmerBox(widthFraction = 0.6f, height = LettaDimens.Space.lg)
        ShimmerBox(widthFraction = 1f, height = LettaDimens.Space.md)
        ShimmerBox(widthFraction = 0.8f, height = LettaDimens.Space.md)
    }
}

@Composable
fun ShimmerGrid(
    columns: Int = 3,
    rows: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        repeat(rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .clip(RoundedCornerShape(LettaDimens.Radius.md))
                            .background(shimmerColor())
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerConversationList(
    itemCount: Int = 8,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        repeat(itemCount) {
            ShimmerConversationCard()
        }
    }
}

@Composable
fun ShimmerConversationCard(
    modifier: Modifier = Modifier,
) {
    val color = shimmerColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LettaDimens.Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ShimmerBox(widthFraction = 0.7f, height = LettaDimens.Space.lg)
        Row(
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(LettaDimens.Space.lg)
                    .clip(RoundedCornerShape(LettaDimens.Space.xs))
                    .background(color)
            )
            ShimmerBox(widthFraction = 0.4f, height = LettaDimens.Space.md)
        }
        ShimmerBox(widthFraction = 0.25f, height = LettaDimens.Space.md)
    }
}
