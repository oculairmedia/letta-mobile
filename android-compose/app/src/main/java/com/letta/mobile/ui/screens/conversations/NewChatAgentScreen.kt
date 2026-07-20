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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadataMonospace
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatAgentScreen(
    agents: List<Agent>,
    onBack: () -> Unit,
    onAgentSelected: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredAgents = remember(agents, query) {
        val needle = query.trim()
        if (needle.isBlank()) {
            agents
        } else {
            agents.filter { agent ->
                agent.name.contains(needle, ignoreCase = true) ||
                    agent.description.orEmpty().contains(needle, ignoreCase = true) ||
                    agent.model.orEmpty().contains(needle, ignoreCase = true)
            }
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Type an agent name or model") },
                leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )

            when {
                agents.isEmpty() -> EmptyState(
                    icon = LettaIcons.AccountCircle,
                    message = "Create an agent before starting a new chat.",
                    modifier = Modifier.fillMaxSize(),
                )
                filteredAgents.isEmpty() -> EmptyState(
                    icon = LettaIcons.Search,
                    message = "No matching agents. Try another name, description, or model.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        Text(
                            text = "Agents",
                            style = MaterialTheme.typography.sectionTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    items(filteredAgents, key = { it.id.value }) { agent ->
                        Card(
                            onClick = { onAgentSelected(agent) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AgentInitialAvatar(agent.name)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = agent.name.ifBlank { "Unnamed agent" },
                                        style = MaterialTheme.typography.listItemHeadline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = agent.description?.takeIf(String::isNotBlank)
                                            ?: agent.model?.takeIf(String::isNotBlank)
                                            ?: agent.id.value,
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentInitialAvatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleLarge)
        }
    }
}
