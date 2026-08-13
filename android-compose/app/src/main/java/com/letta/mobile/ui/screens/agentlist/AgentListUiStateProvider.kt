package com.letta.mobile.ui.screens.agentlist

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import kotlinx.collections.immutable.persistentListOf

val sampleAgents = listOf(
    Agent(id = AgentId("1"), name = "General Assistant", model = "letta/letta-free", description = "A general-purpose agent", tags = persistentListOf("default", "chat")),
    Agent(id = AgentId("2"), name = "Code Helper", model = "openai/gpt-4o", description = "Specialized in programming", tags = persistentListOf("code")),
    Agent(id = AgentId("3"), name = "Research Bot", model = "anthropic/claude-3.5-sonnet", tags = persistentListOf("research", "analysis")),
)

class AgentListUiStateProvider : PreviewParameterProvider<AgentListUiState> {
    override val values = sequenceOf(
        AgentListUiState(),
        AgentListUiState(searchQuery = "code"),
        AgentListUiState(isCreating = true),
        AgentListUiState(error = "Failed to load agents"),
    )
}
