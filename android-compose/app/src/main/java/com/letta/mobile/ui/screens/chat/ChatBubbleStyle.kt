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
fun bubbleStyle(role: String, isStreaming: Boolean = false, isError: Boolean = false): ChatBubbleStyle {
    val colorScheme = MaterialTheme.colorScheme
    val accent = colorScheme.primary

    // letta-mobile-5s1n: server-emitted error frames render with the
    // destructive accent so the user sees that the run aborted.
    if (isError) {
        return ChatBubbleStyle(
            alignEnd = false,
            containerColor = colorScheme.errorContainer,
            roleColor = colorScheme.onErrorContainer,
            roleLabel = "Error",
        )
    }

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
