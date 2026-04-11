package com.letta.mobile.ui.screens.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class ChatBubbleStyle(
    val alignEnd: Boolean,
    val containerColor: Color,
    val roleColor: Color,
    val roleLabel: String,
)

@Composable
fun bubbleStyle(role: String, isStreaming: Boolean = false): ChatBubbleStyle {
    val colorScheme = MaterialTheme.colorScheme
    val accent = colorScheme.primary

    return when (role) {
        "user" -> ChatBubbleStyle(
            alignEnd = true,
            containerColor = colorScheme.primaryContainer,
            roleColor = colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            roleLabel = "You",
        )

        "tool" -> ChatBubbleStyle(
            alignEnd = false,
            containerColor = colorScheme.secondaryContainer,
            roleColor = colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            roleLabel = "Tool",
        )

        else -> ChatBubbleStyle(
            alignEnd = false,
            containerColor = colorScheme.surfaceContainerLow,
            roleColor = accent,
            roleLabel = if (isStreaming) "Agent · Live" else "Agent",
        )
    }
}
