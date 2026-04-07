package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.domain.AgentSearch
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAgent: (String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    viewModel: AgentListViewModel = hiltViewModel(),
    agentSearch: AgentSearch = remember { AgentSearch() }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val agentsPaged = viewModel.agentsPaged.collectAsLazyPagingItems()
    var showCreateDialog by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    val searchQuery = uiState.searchQuery
    val allAgents = uiState.allAgents

    // Get agents from paging for display when not searching
    val loadedAgents by remember(agentsPaged.itemCount) {
        derivedStateOf {
            (0 until agentsPaged.itemCount).mapNotNull { agentsPaged[it] }
        }
    }

    // Use allAgents for search (fetched from API), fall back to loadedAgents
    val searchableAgents = if (allAgents.isNotEmpty()) allAgents else loadedAgents

    // Filter using fuzzy search
    val filteredAgents by remember(searchableAgents, searchQuery) {
        derivedStateOf {
            agentSearch.search(searchableAgents, searchQuery)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.common_agents)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                )

                // Docked Search Bar below the TopAppBar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    DockedSearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = { viewModel.updateSearchQuery(it) },
                                onSearch = { searchActive = false },
                                expanded = searchActive,
                                onExpandedChange = { searchActive = it },
                                placeholder = { Text(stringResource(R.string.screen_agents_search_hint)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_cancel))
                                        }
                                    }
                                }
                            )
                        },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { traversalIndex = 0f }
                    ) {
                        // Show suggestions when search is active
                        if (searchQuery.isNotEmpty() && filteredAgents.isNotEmpty()) {
                            filteredAgents.take(5).forEach { agent ->
                                ListItem(
                                    headlineContent = { Text(agent.name) },
                                    supportingContent = agent.model?.let { { Text(it) } },
                                    leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                                    modifier = Modifier
                                        .clickable {
                                            searchActive = false
                                            onNavigateToAgent(agent.id)
                                        }
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Create Agent")
            }
        }
    ) { paddingValues ->
        AgentListContent(
            agentsPaged = agentsPaged,
            filteredAgents = filteredAgents,
            searchQuery = searchQuery,
            onAgentClick = { onNavigateToAgent(it.id) },
            onAgentLongPress = { onNavigateToEditAgent(it.id) },
            onDeleteAgent = { viewModel.deleteAgent(it.id) },
            onRetry = { agentsPaged.retry() },
            modifier = Modifier.padding(paddingValues)
        )
    }

    // Error snackbar from ViewModel
    uiState.error?.let { error ->
        // Could show a snackbar here
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
    agentsPaged: LazyPagingItems<Agent>,
    filteredAgents: List<Agent>,
    searchQuery: String,
    onAgentClick: (Agent) -> Unit,
    onAgentLongPress: (Agent) -> Unit,
    onDeleteAgent: (Agent) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loadState = agentsPaged.loadState

    when {
        // Initial loading
        loadState.refresh is LoadState.Loading && agentsPaged.itemCount == 0 -> {
            LoadingIndicator()
        }
        // Initial load error
        loadState.refresh is LoadState.Error && agentsPaged.itemCount == 0 -> {
            val error = (loadState.refresh as LoadState.Error).error
            ErrorContent(
                message = error.message ?: "Failed to load agents",
                onRetry = onRetry,
                modifier = modifier
            )
        }
        // Empty state (no search query, no results)
        agentsPaged.itemCount == 0 && searchQuery.isBlank() -> {
            EmptyState(
                icon = Icons.Default.SmartToy,
                message = "No agents yet",
                modifier = modifier.fillMaxSize()
            )
        }
        // Empty search results
        filteredAgents.isEmpty() && searchQuery.isNotBlank() -> {
            EmptyState(
                icon = Icons.Default.SmartToy,
                message = "No agents matching \"$searchQuery\"",
                modifier = modifier.fillMaxSize()
            )
        }
        // Show content
        else -> {
            val displayAgents = if (searchQuery.isNotBlank()) filteredAgents else null

            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (displayAgents != null) {
                    // When searching, use filtered list
                    items(
                        count = displayAgents.size,
                        key = { "${displayAgents[it].id}-$it" }
                    ) { index ->
                        val agent = displayAgents[index]
                        AgentCard(
                            agent = agent,
                            onClick = { onAgentClick(agent) },
                            onLongPress = { onAgentLongPress(agent) },
                            onDelete = { onDeleteAgent(agent) }
                        )
                    }
                } else {
                    // When not searching, use paged data for infinite scroll
                    items(
                        count = agentsPaged.itemCount,
                        key = { "${agentsPaged[it]?.id ?: "loading"}-$it" }
                    ) { index ->
                        val agent = agentsPaged[index]
                        if (agent != null) {
                            AgentCard(
                                agent = agent,
                                onClick = { onAgentClick(agent) },
                                onLongPress = { onAgentLongPress(agent) },
                                onDelete = { onDeleteAgent(agent) }
                            )
                        }
                    }

                    // Loading more indicator
                    if (loadState.append is LoadState.Loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    // Load more error
                    if (loadState.append is LoadState.Error) {
                        item {
                            val error = (loadState.append as LoadState.Error).error
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = error.message ?: "Failed to load more",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(onClick = onRetry) {
                                        Text("Retry")
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
            title = { Text(stringResource(R.string.screen_agents_dialog_delete_title)) },
            text = { Text(stringResource(R.string.screen_agents_dialog_delete_confirm, )) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
            Text(stringResource(R.string.action_retry))
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
        title = { Text(stringResource(R.string.screen_agents_dialog_create_title)) },
        text = {
            OutlinedTextField(
                value = agentName,
                onValueChange = { agentName = it },
                label = { Text(stringResource(R.string.screen_agents_dialog_name_label)) },
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
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
