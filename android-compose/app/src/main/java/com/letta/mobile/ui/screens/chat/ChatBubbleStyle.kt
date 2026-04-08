package com.letta.mobile.ui.screens.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.customColors

data class ChatBubbleStyle(
    val alignEnd: Boolean,
    val containerColor: Color,
    val borderColor: Color,
    val roleColor: Color,
    val roleLabel: String,
)

@Composable
fun bubbleStyle(role: String, isStreaming: Boolean = false): ChatBubbleStyle {
    val customColors = MaterialTheme.customColors
    val accent = MaterialTheme.colorScheme.primary

    return when (role) {
        "user" -> ChatBubbleStyle(
            alignEnd = true,
            containerColor = customColors.userBubbleBgColor,
            borderColor = customColors.userBubbleBgColor,
            roleColor = Color.White.copy(alpha = 0.7f),
            roleLabel = "You",
        )
        "tool" -> ChatBubbleStyle(
            alignEnd = false,
            containerColor = customColors.toolBubbleBgColor,
            borderColor = customColors.toolBubbleBgColor.copy(alpha = 0.5f),
            roleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            roleLabel = "Tool",
        )
        else -> ChatBubbleStyle(
            alignEnd = false,
            containerColor = customColors.agentBubbleBgColor,
            borderColor = if (isStreaming) accent else MaterialTheme.colorScheme.outlineVariant,
            roleColor = accent,
            roleLabel = if (isStreaming) "Agent \u00B7 Live" else "Agent",
        )
    }
}
