package com.letta.mobile.ui.screens.agentlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAgent: (String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    viewModel: AgentListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.agents)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) {
                                viewModel.updateSearchQuery("")
                            }
                        }) {
                            Icon(
                                if (showSearch) Icons.Default.Clear else Icons.Default.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                    }
                )

                AnimatedVisibility(
                    visible = showSearch,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    OutlinedTextField(
                        value = (uiState as? UiState.Success)?.data?.searchQuery ?: "",
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_agents)) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            val query = (uiState as? UiState.Success)?.data?.searchQuery ?: ""
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cancel))
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Create Agent")
            }
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> LoadingIndicator()
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadAgents() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> {
                val filteredAgents by remember(state.data.agents, state.data.searchQuery) {
                    derivedStateOf {
                        val query = state.data.searchQuery.trim()
                        if (query.isBlank()) {
                            state.data.agents
                        } else {
                            state.data.agents.filter { agent ->
                                agent.name.contains(query, ignoreCase = true) ||
                                    (agent.description?.contains(query, ignoreCase = true) == true) ||
                                    (agent.model?.contains(query, ignoreCase = true) == true) ||
                                    (agent.tags?.any { it.contains(query, ignoreCase = true) } == true)
                            }
                        }
                    }
                }

                AgentListContent(
                    agents = filteredAgents,
                    searchQuery = state.data.searchQuery,
                    onAgentClick = { onNavigateToAgent(it.id) },
                    onAgentLongPress = { onNavigateToEditAgent(it.id) },
                    onDeleteAgent = { viewModel.deleteAgent(it.id) },
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateAgentDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createAgent(name) { agentId ->
                    showCreateDialog = false
                    onNavigateToAgent(agentId)
                }
            }
        )
    }
}

@Composable
private fun AgentListContent(
    agents: List<Agent>,
    searchQuery: String,
    onAgentClick: (Agent) -> Unit,
    onAgentLongPress: (Agent) -> Unit,
    onDeleteAgent: (Agent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (agents.isEmpty()) {
        EmptyState(
            icon = Icons.Default.SmartToy,
            message = if (searchQuery.isBlank()) "No agents yet" else "No agents matching \"$searchQuery\"",
            modifier = modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = agents,
                key = { it.id }
            ) { agent ->
                AgentCard(
                    agent = agent,
                    onClick = { onAgentClick(agent) },
                    onLongPress = { onAgentLongPress(agent) },
                    onDelete = { onDeleteAgent(agent) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentCard(
    agent: Agent,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = agent.model ?: "No model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!agent.tags.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        agent.tags.take(3).forEach { tag ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, "Delete")
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_agent)) },
            text = { Text(stringResource(R.string.confirm_delete_agent, agent.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun CreateAgentDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var agentName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_agent)) },
        text = {
            OutlinedTextField(
                value = agentName,
                onValueChange = { agentName = it },
                label = { Text(stringResource(R.string.agent_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (agentName.isNotBlank()) {
                        onCreate(agentName)
                    }
                },
                enabled = agentName.isNotBlank()
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
