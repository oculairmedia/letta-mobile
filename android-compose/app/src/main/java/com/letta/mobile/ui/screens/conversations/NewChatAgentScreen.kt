package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.mascot.AgentAvatar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSearchMatcher
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadataMonospace
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.theme.LettaDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatAgentScreen(
    agents: List<Agent>,
    onBack: () -> Unit,
    onAgentSelected: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredAgents = rememberFilteredAgents(agents, query)

    Scaffold(
        modifier = modifier,
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text("New chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LettaIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xl),
                placeholder = { Text("Type an agent name or model") },
                leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(LettaDimens.Radius.lg),
            )
            NewChatAgentBody(
                agents = agents,
                filteredAgents = filteredAgents,
                onAgentSelected = onAgentSelected,
            )
        }
    }
}

@Composable
private fun rememberFilteredAgents(agents: List<Agent>, query: String): List<Agent> {
    return remember(agents, query) { filterAgentsForNewChat(agents, query) }
}

private fun filterAgentsForNewChat(agents: List<Agent>, query: String): List<Agent> =
    AgentSearchMatcher.filter(agents, query)

@Composable
private fun NewChatAgentBody(
    agents: List<Agent>,
    filteredAgents: List<Agent>,
    onAgentSelected: (Agent) -> Unit,
) {
    when {
        agents.isEmpty() -> NewChatNoAgentsEmptyState()
        filteredAgents.isEmpty() -> NewChatNoMatchesEmptyState()
        else -> NewChatAgentList(
            agents = filteredAgents,
            onAgentSelected = onAgentSelected,
        )
    }
}

@Composable
private fun NewChatNoAgentsEmptyState() {
    EmptyState(
        icon = LettaIcons.AccountCircle,
        message = "Create an agent before starting a new chat.",
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NewChatNoMatchesEmptyState() {
    EmptyState(
        icon = LettaIcons.Search,
        message = "No matching agents. Try another name, description, or model.",
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NewChatAgentList(
    agents: List<Agent>,
    onAgentSelected: (Agent) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        item {
            Text(
                text = "Agents",
                style = MaterialTheme.typography.sectionTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            )
        }
        items(agents, key = { it.id.value }) { agent ->
            NewChatAgentRow(
                agent = agent,
                onClick = { onAgentSelected(agent) },
            )
        }
    }
}

@Composable
private fun NewChatAgentRow(
    agent: Agent,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LettaDimens.Space.xs),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            AgentAvatar(agentId = agent.id.value, name = agent.name, size = LettaDimens.Orb.railSlotWidth)
            NewChatAgentDetails(
                agent = agent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NewChatAgentDetails(
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = agent.name.ifBlank { "Unnamed agent" },
            style = MaterialTheme.typography.listItemHeadline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = newChatAgentSubtitle(agent),
            style = MaterialTheme.typography.listItemSupporting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val model = agent.model
        if (!agent.description.isNullOrBlank() && !model.isNullOrBlank()) {
            Text(
                text = model,
                style = MaterialTheme.typography.listItemMetadataMonospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun newChatAgentSubtitle(agent: Agent): String {
    return agent.description?.takeIf(String::isNotBlank)
        ?: agent.model?.takeIf(String::isNotBlank)
        ?: agent.id.value
}

// region Previews

private val previewNewChatAgents = listOf(
    Agent(id = AgentId("agent-1"), name = "General Assistant", model = "letta/letta-free", description = "A general-purpose agent"),
    Agent(id = AgentId("agent-2"), name = "Code Helper", model = "openai/gpt-4o", description = "Specialized in programming"),
    Agent(id = AgentId("agent-3"), name = "Research Bot", model = "anthropic/claude-3.5-sonnet"),
)

@PreviewLightDark
@Composable
private fun NewChatAgentListPreview() {
    // Renders the agent list directly: the layoutlib preview renderer cannot
    // execute Material3 TopAppBar (NoSuchMethodError), so the full
    // NewChatAgentScreen scaffold is not previewable here.
    LettaPreviewFrame {
        NewChatAgentList(
            agents = previewNewChatAgents,
            onAgentSelected = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun NewChatNoAgentsEmptyStatePreview() {
    LettaPreviewFrame {
        NewChatNoAgentsEmptyState()
    }
}

// endregion
