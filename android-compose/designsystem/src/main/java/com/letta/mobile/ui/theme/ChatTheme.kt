package com.letta.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

data class ChatColors(
    val userBubble: Color,
    val userText: Color,
    val userRoleLabel: Color,
    val agentBubble: Color,
    val agentText: Color,
    val agentRoleLabel: Color,
    val toolBubble: Color,
    val toolEmoji: Color,
)

data class ChatTypography(
    val messageBody: TextStyle,
    val roleLabel: TextStyle,
    val codeBlock: TextStyle,
    val toolLabel: TextStyle,
    val toolDetail: TextStyle,
    val timestamp: TextStyle,
)

data class ChatShapes(
    val bubbleRadius: Dp = 12.dp,
    val codeBlockRadius: Dp = 8.dp,
    val bubble: Shape = RoundedCornerShape(12.dp),
)

data class ChatDimens(
    val bubblePaddingHorizontal: Dp = 10.dp,
    val bubblePaddingVertical: Dp = 7.dp,
    val bubbleMaxWidthFraction: Float = 0.88f,
    val messageSpacing: Dp = 2.dp,
    val groupedMessageSpacing: Dp = 2.dp,
    val ungroupedMessageSpacing: Dp = 6.dp,
    val contentPaddingHorizontal: Dp = 12.dp,
)

val LocalChatColors = staticCompositionLocalOf<ChatColors> { error("No ChatColors provided") }
// letta-mobile-5e0f.r2: ChatTypography + ChatFontScale flipped from
// `staticCompositionLocalOf` to `compositionLocalOf`. The static variant
// invalidates EVERY reader on any change (no equality check, no skipping),
// which during a continuous pinch-to-zoom forces ~30+ Text composables per
// visible bubble to recompose and re-allocate TextStyle on every pinch
// frame. With the dynamic variant, Compose can use structural equality
// to skip readers whose inputs (memoized TextStyle instances) didn't
// actually change. Combined with the per-fontScale memoization in
// LettaChatTheme below, this makes continuous reflow viable.
val LocalChatTypography = compositionLocalOf<ChatTypography> { error("No ChatTypography provided") }
val LocalChatShapes = staticCompositionLocalOf { ChatShapes() }
val LocalChatDimens = staticCompositionLocalOf { ChatDimens() }
val LocalChatFontScale = compositionLocalOf { 1f }
// letta-mobile-5e0f.r2: signals an active pinch-to-zoom gesture.
// animateContentSize sites in the chat tree gate themselves on this so
// we don't get cascading 150ms height interpolations across many
// bubbles per pinch frame (the actual source of the residual flicker
// after fontScale memoization).
val LocalChatIsPinching = compositionLocalOf { false }

fun TextStyle.scaledBy(factor: Float): TextStyle {
    if (factor == 1f) return this
    return copy(
        fontSize = if (fontSize.isSpecified) (fontSize.value * factor).sp else fontSize,
        lineHeight = if (lineHeight.isSpecified) (lineHeight.value * factor).sp else lineHeight,
    )
}

@Composable
fun LettaChatTheme(
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val materialTypography = MaterialTheme.typography

    // letta-mobile-5e0f.r2: ChatColors only depends on colorScheme — keyed
    // accordingly so it doesn't churn on fontScale-only changes.
    val chatColors = remember(colorScheme) {
        ChatColors(
            userBubble = colorScheme.primaryContainer,
            userText = colorScheme.onPrimaryContainer,
            userRoleLabel = colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            agentBubble = colorScheme.surfaceContainerLow,
            agentText = colorScheme.onSurface,
            agentRoleLabel = colorScheme.primary,
            toolBubble = colorScheme.secondaryContainer,
            toolEmoji = colorScheme.onSecondaryContainer,
        )
    }

    // letta-mobile-5e0f.r2: ChatTypography is memoized by (materialTypography,
    // colorScheme, fontScale). Identical fontScale (e.g. across multiple
    // pointer frames where the value didn't change) returns the SAME instance,
    // so downstream readers skip recomposition. Different fontScale builds
    // a new instance (cheap — six TextStyle allocations) but ALL allocations
    // happen here exactly once per distinct fontScale value, not per Text
    // composable like .scaledBy(LocalChatFontScale.current) used to.
    val chatTypography = remember(materialTypography, colorScheme, fontScale) {
        ChatTypography(
            messageBody = materialTypography.bodyMedium.scaledBy(fontScale),
            roleLabel = materialTypography.chatBubbleSender.copy(letterSpacing = 0.4.sp).scaledBy(fontScale),
            codeBlock = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ).scaledBy(fontScale),
            toolLabel = materialTypography.labelMedium.scaledBy(fontScale),
            toolDetail = materialTypography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
            ).scaledBy(fontScale),
            timestamp = materialTypography.labelSmall.copy(
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ).scaledBy(fontScale),
        )
    }

    CompositionLocalProvider(
        LocalChatColors provides chatColors,
        LocalChatTypography provides chatTypography,
        LocalChatShapes provides ChatShapes(),
        LocalChatDimens provides ChatDimens(),
        LocalChatFontScale provides fontScale,
        content = content,
    )
}

val MaterialTheme.chatColors: ChatColors
    @Composable @ReadOnlyComposable get() = LocalChatColors.current

val MaterialTheme.chatTypography: ChatTypography
    @Composable @ReadOnlyComposable get() = LocalChatTypography.current

val MaterialTheme.chatShapes: ChatShapes
    @Composable @ReadOnlyComposable get() = LocalChatShapes.current

val MaterialTheme.chatDimens: ChatDimens
    @Composable @ReadOnlyComposable get() = LocalChatDimens.current
