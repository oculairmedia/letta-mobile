package com.letta.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.statefulFadingEdges(
    scrollState: LazyListState,
    backgroundColor: Color,
    fadeLength: Dp = 32.dp,
): Modifier = composed {
    val canScrollBackward = scrollState.firstVisibleItemIndex > 0 ||
        scrollState.firstVisibleItemScrollOffset > 0
    val visibleItems = scrollState.layoutInfo.visibleItemsInfo
    val canScrollForward = visibleItems.lastOrNull()?.let { lastVisible ->
        lastVisible.index < scrollState.layoutInfo.totalItemsCount - 1 ||
            lastVisible.offset + lastVisible.size > scrollState.layoutInfo.viewportEndOffset
    } ?: false

    val leftAlpha = animateFloatAsState(
        targetValue = if (canScrollBackward) EdgeVisibleAlpha else EdgeHiddenAlpha,
        animationSpec = tween(durationMillis = EdgeFadeMillis),
        label = "leftFadingEdgeAlpha",
    )
    val rightAlpha = animateFloatAsState(
        targetValue = if (canScrollForward) EdgeVisibleAlpha else EdgeHiddenAlpha,
        animationSpec = tween(durationMillis = EdgeFadeMillis),
        label = "rightFadingEdgeAlpha",
    )

    drawWithContent {
        drawContent()
        val fadeWidth = fadeLength.toPx().coerceAtMost(size.width / 2f)
        if (fadeWidth <= 0f) return@drawWithContent

        if (leftAlpha.value > EdgeHiddenAlpha) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(backgroundColor.copy(alpha = leftAlpha.value), Color.Transparent),
                    startX = 0f,
                    endX = fadeWidth,
                ),
                size = Size(fadeWidth, size.height),
            )
        }
        if (rightAlpha.value > EdgeHiddenAlpha) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, backgroundColor.copy(alpha = rightAlpha.value)),
                    startX = size.width - fadeWidth,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - fadeWidth, 0f),
                size = Size(fadeWidth, size.height),
            )
        }
    }
}

private const val EdgeFadeMillis = 300
private const val EdgeHiddenAlpha = 0f
private const val EdgeVisibleAlpha = 1f
