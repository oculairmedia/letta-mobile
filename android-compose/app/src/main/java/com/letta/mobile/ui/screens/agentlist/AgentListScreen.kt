package com.letta.mobile.ui.screens.agentlist

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
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
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ModelDropdown
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerGrid
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.screens.tools.ToolPickerDialog
import com.letta.mobile.ui.icons.LettaIcons

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
    var showImportDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingImportOverrideTools by remember { mutableStateOf(true) }
    var pendingImportStripMessages by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            snackbar.dispatch(context.getString(R.string.screen_agents_import_read_failed))
        } else {
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.screen_agents_import_default_filename)
            viewModel.importAgent(
                fileName = fileName,
                fileBytes = bytes,
                overrideName = pendingImportName,
                overrideExistingTools = pendingImportOverrideTools,
                stripMessages = pendingImportStripMessages,
            ) { response ->
                val importedId = response.agentIds.firstOrNull()
                snackbar.dispatch(
                    context.getString(
                        if (response.agentIds.size == 1) R.string.screen_agents_import_success_single else R.string.screen_agents_import_success_multiple,
                        response.agentIds.size,
                    )
                )
                importedId?.let(onNavigateToAgent)
            }
        }
    }

    val filteredAgents = remember(uiState.agents, uiState.searchQuery, uiState.selectedTags) {
        viewModel.getFilteredAgents()
    }

    val favoriteAgent = uiState.favoriteAgentId?.let { favId ->
        uiState.agents.find { it.id == favId }
    }
    val gridAgents = filteredAgents.filter { it.id != uiState.favoriteAgentId }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.common_agents)) },
                    scrollBehavior = scrollBehavior,
                    colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(
                                LettaIcons.FileOpen,
                                contentDescription = stringResource(R.string.action_import_agent),
                            )
                        }
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.updateSearchQuery("")
                        }) {
                            Icon(
                                if (showSearch) LettaIcons.Clear else LettaIcons.Search,
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
                        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(LettaIcons.Clear, contentDescription = stringResource(R.string.action_cancel))
                                }
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showGrid,
                        onClick = { showGrid = false },
                        label = { Text(stringResource(R.string.screen_agents_view_list)) },
                    )
                    FilterChip(
                        selected = showGrid,
                        onClick = { showGrid = true },
                        label = { Text(stringResource(R.string.screen_agents_view_grid)) },
                    )
                }

                val allTags = remember(uiState.agents) { viewModel.getAllTags() }
                if (allTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = uiState.selectedTags.isEmpty(),
                                onClick = { viewModel.clearTags() },
                                label = { Text(stringResource(R.string.screen_agents_filter_all)) },
                            )
                        }
                        items(allTags.size) { index ->
                            val tag = allTags[index]
                            FilterChip(
                                selected = tag in uiState.selectedTags,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, "Create Agent")
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
                            icon = LettaIcons.Agent,
                            message = if (uiState.searchQuery.isBlank()) stringResource(R.string.screen_agents_empty)
                            else "No agents matching \"${uiState.searchQuery}\"",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        if (showGrid) {
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
                                    CompactAgentCard(
                                        agent = agent,
                                        isFavorite = agent.id == uiState.favoriteAgentId,
                                        onClick = { onNavigateToAgent(agent.id) },
                                        onLongPress = { onNavigateToEditAgent(agent.id) },
                                        onDelete = { viewModel.deleteAgent(agent.id) },
                                        onToggleFavorite = { viewModel.toggleFavorite(agent.id) },
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (favoriteAgent != null && uiState.searchQuery.isBlank()) {
                                    item(key = "favorite-${favoriteAgent.id}") {
                                        FavoriteAgentCard(
                                            agent = favoriteAgent,
                                            onClick = { onNavigateToAgent(favoriteAgent.id) },
                                            onEdit = { onNavigateToEditAgent(favoriteAgent.id) },
                                            onUnfavorite = { viewModel.toggleFavorite(favoriteAgent.id) },
                                        )
                                    }
                                }

                                lazyItems(gridAgents, key = { it.id }) { agent ->
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
    }

    if (showCreateDialog) {
        CreateAgentDialog(
            onDismiss = { showCreateDialog = false },
            availableTools = uiState.availableTools,
            llmModels = uiState.llmModels,
            embeddingModels = uiState.embeddingModels,
            onLoadModels = { viewModel.loadAvailableModels() },
            onCreate = { params ->
                viewModel.createAgent(params) { agentId ->
                    showCreateDialog = false
                    onNavigateToAgent(agentId)
                }
            },
        )
    }

    if (showImportDialog) {
        ImportAgentDialog(
            isImporting = uiState.isImporting,
            onDismiss = { showImportDialog = false },
            onImport = { overrideName, overrideExistingTools, stripMessages ->
                pendingImportName = overrideName
                pendingImportOverrideTools = overrideExistingTools
                pendingImportStripMessages = stripMessages
                importLauncher.launch(arrayOf("application/json", "text/plain"))
                showImportDialog = false
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
                    imageVector = LettaIcons.Star,
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
                    Icon(LettaIcons.Agent, contentDescription = "Edit", tint = contentColor)
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
                val toolCount = agent.tools.size
                val blockCount = agent.blocks.size
                InfoChip(label = "Tools", value = toolCount.toString(), color = subtleColor)
                InfoChip(label = "Memory", value = "$blockCount blocks", color = subtleColor)
                agent.modelSettings?.temperature?.let {
                    InfoChip(label = "Temp", value = String.format("%.1f", it), color = subtleColor)
                }
                if (agent.enableSleeptime == true) {
                    InfoChip(label = "Sleep", value = "On", color = subtleColor)
                }
            }

            agent.tags.takeIf { it.isNotEmpty() }?.let { tags ->
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    val toolCount = agent.tools.size
    val blockCount = agent.blocks.size

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                },
            ),
        shape = RoundedCornerShape(28.dp),
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceBright
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .optionalSharedElement("agent_avatar_${agent.id}"),
                shape = RoundedCornerShape(16.dp),
                color = Color(agentColor),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LettaIcons.Agent,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isFavorite) {
                        Icon(
                            imageVector = LettaIcons.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { showContextMenu = true }) {
                        Icon(LettaIcons.MoreVert, contentDescription = null)
                    }
                }

                agent.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AgentMetaChip(text = agent.model ?: "No model")
                    AgentMetaChip(text = "$toolCount ${stringResource(R.string.common_tools)}")
                    AgentMetaChip(text = "$blockCount memory")
                    agent.embedding?.takeIf { it.isNotBlank() }?.let { embedding ->
                        AgentMetaChip(text = embedding)
                    }
                    if (agent.enableSleeptime == true) {
                        AgentMetaChip(text = "Sleep")
                    }
                    agent.tags.take(3).forEach { tag ->
                        AgentTagChip(tag = tag)
                    }
                    if (agent.tags.size > 3) {
                        AgentMetaChip(text = "+${agent.tags.size - 3}")
                    }
                }

                agent.createdAt?.let { createdAt ->
                    Text(
                        text = "Created ${com.letta.mobile.util.formatRelativeTime(createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = agent.name,
    ) {
        ActionSheetItem(
            text = if (isFavorite) "Remove Favorite" else "Set as Favorite",
            icon = if (isFavorite) LettaIcons.Favorite else LettaIcons.FavoriteBorder,
            onClick = { showContextMenu = false; onToggleFavorite() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; onLongPress() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm, agent.name),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactAgentCard(
    agent: Agent,
    isFavorite: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val agentColor = remember(agent.id) {
        val hue = (agent.id.hashCode().and(0xFF)) * 360f / 256f
        android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.3f, 0.9f))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                },
            ),
        shape = RoundedCornerShape(28.dp),
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceBright
        },
        tonalElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Color(agentColor)),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = LettaIcons.Agent,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .optionalSharedElement("agent_avatar_${agent.id}"),
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
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = agent.name,
    ) {
        ActionSheetItem(
            text = if (isFavorite) "Remove Favorite" else "Set as Favorite",
            icon = if (isFavorite) LettaIcons.Favorite else LettaIcons.FavoriteBorder,
            onClick = { showContextMenu = false; onToggleFavorite() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; onLongPress() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm, agent.name),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )
}

@Composable
private fun AgentMetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentTagChip(tag: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = tag,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateAgentDialog(
    onDismiss: () -> Unit,
    availableTools: List<Tool> = emptyList(),
    llmModels: List<LlmModel> = emptyList(),
    embeddingModels: List<EmbeddingModel> = emptyList(),
    onLoadModels: () -> Unit = {},
    onCreate: (AgentCreateParams) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var embedding by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("1.0") }
    var maxOutputTokens by remember { mutableStateOf("4096") }
    var parallelToolCalls by remember { mutableStateOf(true) }
    var enableSleeptime by remember { mutableStateOf(false) }
    var includeBaseTools by remember { mutableStateOf(true) }
    var selectedToolIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var showToolPicker by remember { mutableStateOf(false) }
    val embeddingDropdownModels = remember(embeddingModels) {
        embeddingModels.map {
            LlmModel(
                id = it.id,
                name = it.name,
                handle = it.handle,
                providerType = it.providerType,
            )
        }
    }

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
                ModelDropdown(
                    selectedModel = model,
                    models = llmModels,
                    onModelSelected = { model = it },
                    onLoadModels = onLoadModels,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.common_model),
                )
                ModelDropdown(
                    selectedModel = embedding,
                    models = embeddingDropdownModels,
                    onModelSelected = { embedding = it },
                    onLoadModels = onLoadModels,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.screen_agent_edit_embedding_model),
                )
                Text(
                    text = stringResource(R.string.screen_agents_create_advanced_model_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = providerType,
                    onValueChange = { providerType = it },
                    label = { Text(stringResource(R.string.common_provider)) },
                    placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { value ->
                        if (value.isBlank() || value.toDoubleOrNull() != null) {
                            temperature = value
                        }
                    },
                    label = { Text(stringResource(R.string.screen_agent_edit_temperature_value, temperature.toFloatOrNull() ?: 0f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxOutputTokens,
                    onValueChange = { value ->
                        if (value.isBlank() || value.toIntOrNull() != null) {
                            maxOutputTokens = value
                        }
                    },
                    label = { Text(stringResource(R.string.common_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.common_parallel_tool_calls), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = parallelToolCalls, onCheckedChange = { parallelToolCalls = it })
                }
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
                    Text(stringResource(R.string.common_enable_sleeptime), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enableSleeptime, onCheckedChange = { enableSleeptime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.screen_agents_create_include_base_tools), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeBaseTools, onCheckedChange = { includeBaseTools = it })
                }
                Text(
                    text = stringResource(R.string.common_tools),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (selectedToolIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_tools_empty_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.screen_agents_create_selected_tools_count, selectedToolIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = { showToolPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(LettaIcons.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agents_create_select_tools))
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
                            modelSettings = ModelSettings(
                                providerType = providerType.ifBlank { null },
                                temperature = temperature.toDoubleOrNull(),
                                maxOutputTokens = maxOutputTokens.toIntOrNull(),
                                parallelToolCalls = parallelToolCalls,
                            ),
                            toolIds = selectedToolIds.ifEmpty { null },
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

    if (showToolPicker) {
        ToolPickerDialog(
            tools = availableTools,
            selectedToolIds = selectedToolIds,
            title = stringResource(R.string.screen_agents_create_select_tools),
            onDismiss = { showToolPicker = false },
            onConfirm = { selectedIds ->
                selectedToolIds = selectedIds
                showToolPicker = false
            },
        )
    }
}

@Composable
private fun ImportAgentDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (overrideName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    var overrideName by remember { mutableStateOf("") }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_agents_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.screen_agents_import_helper),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = overrideName,
                    onValueChange = { overrideName = it },
                    label = { Text(stringResource(R.string.screen_agents_import_override_name)) },
                    placeholder = { Text(stringResource(R.string.screen_agents_import_override_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_override_tools_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.screen_agents_import_override_tools_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_agents_import_strip_messages_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.screen_agents_import_strip_messages_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(overrideName.ifBlank { null }, overrideExistingTools, stripMessages)
                },
                enabled = !isImporting,
            ) {
                Text(stringResource(R.string.action_choose_file))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
