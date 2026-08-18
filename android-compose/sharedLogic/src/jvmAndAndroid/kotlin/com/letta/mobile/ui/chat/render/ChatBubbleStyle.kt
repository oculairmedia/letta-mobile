package com.letta.mobile.ui.chat.render

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
fun bubbleStyle(
    role: String,
    isStreaming: Boolean = false,
    isError: Boolean = false,
    // letta-mobile-bccty: when true, the bubble renders as an inter-agent
    // (a2a) message — a distinct tertiary-tinted container + "Inter-agent"
    // role label so the user can distinguish it from their own prompts
    // and from the local assistant's replies. The bubble shape and layout
    // stay the same; only color and label change.
    isAgentMessage: Boolean = false,
): ChatBubbleStyle {
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

    // letta-mobile-bccty: inter-agent messages render in the tertiary
    // container with a tertiary role label. They use the same role-driven
    // alignment (the local agent is still the "author" from the chat's
    // perspective), but the role label switches to "Inter-agent" so the
    // header above the bubble and the bubble's own tint both say "this
    // is a different agent."
    if (isAgentMessage) {
        return ChatBubbleStyle(
            alignEnd = role == "user",
            containerColor = colorScheme.tertiaryContainer,
            roleColor = colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
            roleLabel = "Inter-agent",
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
