package com.letta.mobile.ui.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.motion.ChatMotionPolicy
import com.letta.mobile.ui.motion.ChatMotionTokens
import com.letta.mobile.ui.motion.rememberChatMotionPolicy

/**
 * Accessible shimmering live-status text primitive.
 *
 * Displays a restrained traveling highlight sweep over readable text when [active] is true
 * and motion policy allows infinite animations. Degrades cleanly to static text at a fixed
 * alpha when terminal, disabled, or under reduced-motion settings.
 *
 * Exposes stable text semantics to TalkBack without per-frame accessibility tree invalidation.
 *
 * @param text Content string to display.
 * @param modifier Composable modifier.
 * @param active Whether the status represents an active running task.
 * @param style Text typography style.
 * @param baseColor Base color of the status text.
 * @param highlightColor Traveling highlight color during active shimmer.
 * @param shimmerProgress Optional external progress override (0..1) for shared clock sync.
 * @param motionPolicy Motion policy to query for timing, alpha, and reduced-motion rules.
 */
@Composable
fun LiveStatusText(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    style: TextStyle = LocalTextStyle.current,
    baseColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface,
    shimmerProgress: Float? = null,
    motionPolicy: ChatMotionPolicy = rememberChatMotionPolicy(),
) {
    val shouldAnimate = active && motionPolicy.runningCue.allowInfiniteAnimation

    val progress: Float = if (shimmerProgress != null) {
        shimmerProgress
    } else if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "LiveStatusTextShimmer")
        val spec = (motionPolicy.runningCue.spec as? InfiniteRepeatableSpec<Float>)
            ?: infiniteRepeatable(tween(ChatMotionTokens.RUNNING_CUE_DURATION_MILLIS))
        val animatedProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spec,
            label = "LiveStatusTextProgress",
        )
        animatedProgress
    } else {
        0f
    }

    val shimmerModifier = if (shouldAnimate) {
        Modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val widthPx = size.width
                val bandWidthPx = (widthPx * 0.4f).coerceAtLeast(60.dp.toPx())
                val centerPx = progress * (widthPx + 2f * bandWidthPx) - bandWidthPx
                val brush = Brush.linearGradient(
                    colors = listOf(baseColor, highlightColor, baseColor),
                    start = Offset(centerPx - bandWidthPx, 0f),
                    end = Offset(centerPx + bandWidthPx, 0f),
                    tileMode = TileMode.Clamp,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(
                        brush = brush,
                        blendMode = BlendMode.SrcIn,
                    )
                }
            }
    } else {
        Modifier
    }

    val textColor = if (shouldAnimate) {
        baseColor
    } else {
        baseColor.copy(alpha = motionPolicy.runningCue.staticAlpha)
    }

    Text(
        text = text,
        modifier = modifier.then(shimmerModifier),
        style = style,
        color = textColor,
        softWrap = true,
    )
}
