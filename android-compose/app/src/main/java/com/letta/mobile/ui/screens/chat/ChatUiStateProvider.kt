package com.letta.mobile.ui.screens.chat

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.UiState
import kotlinx.collections.immutable.persistentListOf

val sampleMessages = persistentListOf(
    UiMessage(id = "1", role = "user", content = "Hello! Can you help me?", timestamp = "2024-03-15T10:00:00Z"),
    UiMessage(id = "2", role = "assistant", content = "Of course! I'd be happy to help. What would you like to know?", timestamp = "2024-03-15T10:00:05Z"),
    UiMessage(id = "3", role = "user", content = "What is Kotlin?", timestamp = "2024-03-15T10:01:00Z"),
    UiMessage(
        id = "4", role = "assistant",
        content = "**Kotlin** is a modern programming language that targets the JVM, Android, JavaScript, and Native platforms.\n\nKey features:\n- Null safety\n- Extension functions\n- Coroutines for async code\n- Data classes",
        timestamp = "2024-03-15T10:01:10Z"
    ),
    UiMessage(id = "5", role = "assistant", content = "Let me look that up for you.", timestamp = "2024-03-15T10:02:00Z", isReasoning = true),
    UiMessage(
        id = "6", role = "tool", content = "", timestamp = "2024-03-15T10:02:05Z",
        toolCalls = listOf(UiToolCall(name = "web_search", arguments = "{\"query\": \"Kotlin\"}", result = "Found 10 results"))
    ),
    UiMessage(
        id = "7",
        role = "assistant",
        content = "Try one of these follow-ups:",
        timestamp = "2024-03-15T10:02:10Z",
        generatedUi = UiGeneratedComponent(
            name = "suggestion_chips",
            propsJson = "{\"title\":\"Next steps\",\"suggestions\":[{\"label\":\"Explain coroutines\",\"message\":\"Explain Kotlin coroutines\"},{\"label\":\"Show Android example\",\"message\":\"Show an Android Kotlin example\"}]}",
            fallbackText = "Choose a follow-up",
        ),
    ),
)

class ChatUiStateProvider : PreviewParameterProvider<UiState<ChatUiState>> {
    override val values = sequenceOf(
        UiState.Loading,
        UiState.Success(ChatUiState()),
        UiState.Success(ChatUiState(messages = sampleMessages, agentName = "Letta Agent")),
        UiState.Success(ChatUiState(messages = sampleMessages, isStreaming = true, isAgentTyping = true, agentName = "Letta Agent")),
        UiState.Error("Failed to connect to server"),
    )
}
