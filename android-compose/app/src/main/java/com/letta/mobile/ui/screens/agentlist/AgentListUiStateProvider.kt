package com.letta.mobile.ui.screens.agentlist

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.letta.mobile.data.model.Agent

val sampleAgents = listOf(
    Agent(id = "1", name = "General Assistant", model = "letta/letta-free", description = "A general-purpose agent", tags = listOf("default", "chat")),
    Agent(id = "2", name = "Code Helper", model = "openai/gpt-4o", description = "Specialized in programming", tags = listOf("code")),
    Agent(id = "3", name = "Research Bot", model = "anthropic/claude-3.5-sonnet", tags = listOf("research", "analysis")),
)

class AgentListUiStateProvider : PreviewParameterProvider<AgentListUiState> {
    override val values = sequenceOf(
        AgentListUiState(),
        AgentListUiState(searchQuery = "code"),
        AgentListUiState(isCreating = true),
        AgentListUiState(error = "Failed to load agents"),
    )
}
