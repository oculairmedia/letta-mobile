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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    val bubbleRadius: Dp = LettaChatTokens.shapes.bubbleRadiusDp.dp,
    val codeBlockRadius: Dp = LettaChatTokens.shapes.codeBlockRadiusDp.dp,
    val bubble: Shape = RoundedCornerShape(LettaChatTokens.shapes.bubbleRadiusDp.dp),
)

data class ChatDimens(
    val bubblePaddingHorizontal: Dp = LettaChatTokens.dimens.bubblePaddingHorizontalDp.dp,
    val bubblePaddingVertical: Dp = LettaChatTokens.dimens.bubblePaddingVerticalDp.dp,
    val bubbleMaxWidthFraction: Float = LettaChatTokens.dimens.bubbleMaxWidthFraction,
    val messageSpacing: Dp = LettaChatTokens.dimens.messageSpacingDp.dp,
    val groupedMessageSpacing: Dp = LettaChatTokens.dimens.groupedMessageSpacingDp.dp,
    val ungroupedMessageSpacing: Dp = LettaChatTokens.dimens.ungroupedMessageSpacingDp.dp,
    val contentPaddingHorizontal: Dp = LettaChatTokens.dimens.contentPaddingHorizontalDp.dp,
)

val LocalChatColors = staticCompositionLocalOf<ChatColors> { error("No ChatColors provided") }
// ChatTypography + ChatFontScale use `compositionLocalOf` rather than
// `staticCompositionLocalOf` so committed font-scale changes can skip
// readers whose memoized TextStyle inputs did not actually change. This
// keeps the one-shot pinch commit cheap enough, but it is not a license to
// push raw pointer-frame fontScale updates through the whole chat tree.
// Continuous text reflow during pinch has to be throttled and profiled before
// it replaces the current GPU-layer pinch preview in ChatMessageList.
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
            userRoleLabel = colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
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
            roleLabel = materialTypography.chatBubbleSender
                .copy(letterSpacing = LettaChatTokens.typography.roleLabelLetterSpacingSp.sp)
                .scaledBy(fontScale),
            codeBlock = TextStyle(
                fontFamily = LettaCodeFont,
                fontSize = LettaChatTokens.typography.codeBlockFontSizeSp.sp,
                lineHeight = LettaChatTokens.typography.codeBlockLineHeightSp.sp,
            ).scaledBy(fontScale),
            toolLabel = materialTypography.labelMedium.scaledBy(fontScale),
            toolDetail = materialTypography.labelSmall.copy(
                fontFamily = LettaCodeFont,
            ).scaledBy(fontScale),
            timestamp = materialTypography.labelSmall.copy(
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
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
    @Suppress("UnusedReceiverParameter")
    @Composable @ReadOnlyComposable get() = LocalChatColors.current

val MaterialTheme.chatTypography: ChatTypography
    @Suppress("UnusedReceiverParameter")
    @Composable @ReadOnlyComposable get() = LocalChatTypography.current

val MaterialTheme.chatShapes: ChatShapes
    @Suppress("UnusedReceiverParameter")
    @Composable @ReadOnlyComposable get() = LocalChatShapes.current

val MaterialTheme.chatDimens: ChatDimens
    @Suppress("UnusedReceiverParameter")
    @Composable @ReadOnlyComposable get() = LocalChatDimens.current
