package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAgent: (String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    val filteredAgents by remember(uiState.agents, uiState.searchQuery) {
        derivedStateOf { viewModel.getFilteredAgents() }
    }

    val favoriteAgent = uiState.favoriteAgentId?.let { favId ->
        uiState.agents.find { it.id == favId }
    }
    val gridAgents = filteredAgents.filter { it.id != uiState.favoriteAgentId }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.common_agents)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.updateSearchQuery("")
                        }) {
                            Icon(
                                if (showSearch) Icons.Default.Clear else Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search),
                            )
                        }
                    }
                )

                if (showSearch) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.screen_agents_search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_cancel))
                                }
                            }
                        },
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
        when {
            uiState.isLoading -> ShimmerGrid(modifier = Modifier.padding(paddingValues))
            uiState.error != null && uiState.agents.isEmpty() -> ErrorContent(
                message = uiState.error!!,
                onRetry = { viewModel.loadAgents() },
                modifier = Modifier.padding(paddingValues),
            )
            else -> {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.padding(paddingValues).fillMaxSize(),
                ) {
                    if (filteredAgents.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.SmartToy,
                            message = if (uiState.searchQuery.isBlank()) stringResource(R.string.screen_agents_empty)
                            else "No agents matching \"${uiState.searchQuery}\"",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (favoriteAgent != null && uiState.searchQuery.isBlank()) {
                                item(
                                    key = "favorite-${favoriteAgent.id}",
                                    span = { GridItemSpan(3) },
                                ) {
                                    FavoriteAgentCard(
                                        agent = favoriteAgent,
                                        onClick = { onNavigateToAgent(favoriteAgent.id) },
                                        onEdit = { onNavigateToEditAgent(favoriteAgent.id) },
                                        onUnfavorite = { viewModel.toggleFavorite(favoriteAgent.id) },
                                    )
                                }
                            }

                            items(gridAgents, key = { it.id }) { agent ->
                                AgentCard(
                                    agent = agent,
                                    isFavorite = agent.id == uiState.favoriteAgentId,
                                    onClick = { onNavigateToAgent(agent.id) },
                                    onLongPress = { onNavigateToEditAgent(agent.id) },
                                    onDelete = { viewModel.deleteAgent(agent.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(agent.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAgentDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { params ->
                viewModel.createAgent(params) { agentId ->
                    showCreateDialog = false
                    onNavigateToAgent(agentId)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteAgentCard(
    agent: Agent,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onUnfavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val subtleColor = contentColor.copy(alpha = 0.6f)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.SmartToy, contentDescription = "Edit", tint = contentColor)
                }
            }

            agent.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InfoChip(label = "Model", value = agent.model ?: "—", color = subtleColor)
                agent.embedding?.let { InfoChip(label = "Embed", value = it, color = subtleColor) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val toolCount = agent.tools?.size ?: 0
                val blockCount = agent.blocks?.size ?: 0
                InfoChip(label = "Tools", value = toolCount.toString(), color = subtleColor)
                InfoChip(label = "Memory", value = "$blockCount blocks", color = subtleColor)
                agent.modelSettings?.temperature?.let {
                    InfoChip(label = "Temp", value = String.format("%.1f", it), color = subtleColor)
                }
                if (agent.enableSleeptime == true) {
                    InfoChip(label = "Sleep", value = "On", color = subtleColor)
                }
            }

            agent.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(4).forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    if (tags.size > 4) {
                        Text(
                            "+${tags.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = subtleColor,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }

            agent.createdAt?.let { created ->
                Text(
                    text = "Created ${com.letta.mobile.util.formatRelativeTime(created)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtleColor.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.6f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentCard(
    agent: Agent,
    isFavorite: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val agentColor = remember(agent.id) {
        val hue = (agent.id.hashCode().and(0xFF)) * 360f / 256f
        android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.3f, 0.9f))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                },
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color(agentColor))
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = agent.model ?: "No model",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (isFavorite) "Remove Favorite" else "Set as Favorite")
                },
                onClick = { showContextMenu = false; onToggleFavorite() },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = { showContextMenu = false; onLongPress() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { showContextMenu = false; showDeleteDialog = true },
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.screen_agents_dialog_delete_title)) },
            text = { Text(stringResource(R.string.screen_agents_dialog_delete_confirm, agent.name)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Error, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun CreateAgentDialog(
    onDismiss: () -> Unit,
    onCreate: (AgentCreateParams) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var embedding by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var enableSleeptime by remember { mutableStateOf(false) }
    var includeBaseTools by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_agents_dialog_create_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.common_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.common_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. openai/gpt-4o") },
                )
                OutlinedTextField(
                    value = embedding,
                    onValueChange = { embedding = it },
                    label = { Text("Embedding Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. openai/text-embedding-3-small") },
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.common_system_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enable Sleeptime", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enableSleeptime, onCheckedChange = { enableSleeptime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Include Base Tools", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeBaseTools, onCheckedChange = { includeBaseTools = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && model.isNotBlank() && embedding.isNotBlank()) {
                        onCreate(AgentCreateParams(
                            name = name,
                            description = description.ifBlank { null },
                            model = model,
                            embedding = embedding,
                            system = systemPrompt.ifBlank { null },
                            enableSleeptime = enableSleeptime,
                            includeBaseTools = includeBaseTools,
                        ))
                    }
                },
                enabled = name.isNotBlank() && model.isNotBlank() && embedding.isNotBlank(),
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
