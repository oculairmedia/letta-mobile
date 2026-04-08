package com.letta.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChatColors(
    val userBubble: Color,
    val userBubbleBorder: Color,
    val userText: Color,
    val userRoleLabel: Color,
    val agentBubble: Color,
    val agentBubbleBorder: Color,
    val agentBubbleBorderStreaming: Color,
    val agentText: Color,
    val agentRoleLabel: Color,
    val toolBubble: Color,
    val toolBubbleBorder: Color,
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
    val bubbleBorderWidth: Dp = 1.dp,
    val messageSpacing: Dp = 2.dp,
    val groupedMessageSpacing: Dp = 2.dp,
    val ungroupedMessageSpacing: Dp = 6.dp,
    val contentPaddingHorizontal: Dp = 12.dp,
)

val LocalChatColors = staticCompositionLocalOf<ChatColors> { error("No ChatColors provided") }
val LocalChatTypography = staticCompositionLocalOf<ChatTypography> { error("No ChatTypography provided") }
val LocalChatShapes = staticCompositionLocalOf { ChatShapes() }
val LocalChatDimens = staticCompositionLocalOf { ChatDimens() }

@Composable
fun LettaChatTheme(
    content: @Composable () -> Unit,
) {
    val customColors = MaterialTheme.customColors

    val chatColors = ChatColors(
        userBubble = customColors.userBubbleBgColor,
        userBubbleBorder = customColors.userBubbleBgColor,
        userText = Color.White,
        userRoleLabel = Color.White.copy(alpha = 0.7f),
        agentBubble = customColors.agentBubbleBgColor,
        agentBubbleBorder = MaterialTheme.colorScheme.outlineVariant,
        agentBubbleBorderStreaming = MaterialTheme.colorScheme.primary,
        agentText = MaterialTheme.colorScheme.onSurface,
        agentRoleLabel = MaterialTheme.colorScheme.primary,
        toolBubble = customColors.toolBubbleBgColor,
        toolBubbleBorder = customColors.toolBubbleBgColor.copy(alpha = 0.5f),
        toolEmoji = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val chatTypography = ChatTypography(
        messageBody = MaterialTheme.typography.bodyMedium,
        roleLabel = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        ),
        codeBlock = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        toolLabel = MaterialTheme.typography.labelMedium,
        toolDetail = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
        ),
        timestamp = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    )

    CompositionLocalProvider(
        LocalChatColors provides chatColors,
        LocalChatTypography provides chatTypography,
        LocalChatShapes provides ChatShapes(),
        LocalChatDimens provides ChatDimens(),
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
