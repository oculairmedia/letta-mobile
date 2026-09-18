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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

@Composable
fun MessageSkeleton(
    isUser: Boolean = false,
    index: Int = 0,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = index * 150),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha$index",
    )

    val shape = RoundedCornerShape(LettaDimens.Radius.md)
    val color = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = if (isUser) 200.dp else 260.dp)
                .clip(shape)
                .background(color)
                .alpha(alpha)
                .padding(LettaDimens.Space.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LettaDimens.Space.md)
                        .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(LettaDimens.Space.md)
                        .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
                if (!isUser) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(LettaDimens.Space.md)
                            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

@Composable
fun MessageSkeletonList(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = LettaDimens.Space.sm)) {
        val pattern = listOf(true, false, true, false, false, true, false)
        pattern.forEachIndexed { index, isUser ->
            MessageSkeleton(isUser = isUser, index = index)
        }
    }
}
