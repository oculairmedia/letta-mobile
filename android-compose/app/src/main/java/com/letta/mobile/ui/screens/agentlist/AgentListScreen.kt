package com.letta.mobile.ui.screens.agentlist

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
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
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.ModelDropdown
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerGrid
import com.letta.mobile.ui.components.statefulFadingEdges
import com.letta.mobile.ui.navigation.agentAvatarSharedElementKey
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.screens.tools.ToolPickerDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.StaggeredListItem
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAgent: (String, String?) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    shareContentPreview: String? = null,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingImportOverrideTools by remember { mutableStateOf(true) }
    var pendingImportStripMessages by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarDispatcher.current
    val isShareMode = shareContentPreview != null
    var shareNavigationConsumed by rememberSaveable(shareContentPreview) { mutableStateOf(false) }
    fun selectAgent(agentId: String, agentName: String?) {
        if (isShareMode) {
            if (shareNavigationConsumed) return
            shareNavigationConsumed = true
        }
        onNavigateToAgent(agentId, agentName)
    }
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
                importedId?.let { onNavigateToAgent(it, pendingImportName) }
            }
        }
    }

    val filteredAgents = remember(uiState.agents, uiState.searchQuery, uiState.selectedTags) {
        viewModel.getFilteredAgents()
    }

    val favoriteAgent = uiState.favoriteAgentId?.let { favId ->
        uiState.agents.find { it.id.value == favId }
    }
    val displayAgents = remember(filteredAgents, favoriteAgent, uiState.pinnedAgentIds) {
        resolveAgentListDisplayAgents(
            filteredAgents = filteredAgents,
            favoriteAgent = favoriteAgent,
            pinnedAgentIds = uiState.pinnedAgentIds,
        )
    }
    val visibleFavoriteAgent = displayAgents.visibleFavoriteAgent
    val gridAgents = displayAgents.listAgents

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LargeFlexibleTopAppBar(
                    title = {
                        ExpandableTitleSearch(
                            query = uiState.searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            onClear = { viewModel.updateSearchQuery("") },
                            expanded = isSearchExpanded,
                            onExpandedChange = { isSearchExpanded = it },
                            placeholder = stringResource(R.string.screen_agents_search_hint),
                            openSearchContentDescription = stringResource(R.string.action_search),
                            closeSearchContentDescription = stringResource(R.string.action_close),
                            titleContent = {
                                Text(if (isShareMode) "Share with agent" else stringResource(R.string.common_agents))
                            },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                    colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (!isShareMode) {
                            IconButton(onClick = { showImportDialog = true }) {
                                Icon(
                                    LettaIcons.FileOpen,
                                    contentDescription = stringResource(R.string.action_import_agent),
                                )
                            }
                        }
                    }
                )
                ExpandableSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onClear = { viewModel.updateSearchQuery("") },
                    expanded = isSearchExpanded,
                    placeholder = stringResource(R.string.screen_agents_search_hint),
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    SegmentedButton(
                        selected = !showGrid,
                        onClick = { showGrid = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(stringResource(R.string.screen_agents_view_list)) },
                    )
                    SegmentedButton(
                        selected = showGrid,
                        onClick = { showGrid = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(stringResource(R.string.screen_agents_view_grid)) },
                    )
                }

                val allTags = remember(uiState.agents) { viewModel.getAllTags() }
                if (shareContentPreview != null) {
                    ShareContentPreviewCard(
                        content = shareContentPreview,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (allTags.isNotEmpty()) {
                    val tagRowState = rememberLazyListState()
                    LazyRow(
                        state = tagRowState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statefulFadingEdges(
                                scrollState = tagRowState,
                                backgroundColor = MaterialTheme.colorScheme.surface,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = uiState.selectedTags.isEmpty(),
                                onClick = { viewModel.clearTags() },
                                label = { Text(stringResource(R.string.screen_agents_filter_all)) },
                            )
                        }
                        items(allTags.size, key = { allTags[it] }) { index ->
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
            if (!isShareMode) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(LettaIcons.Add, "Create Agent")
                }
            }
        }
    ) { paddingValues ->
        val agentError = uiState.error
        when {
            uiState.isLoading -> ShimmerGrid(modifier = Modifier.padding(paddingValues))
            agentError != null && uiState.agents.isEmpty() -> ErrorContent(
                message = agentError,
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
                            message = when {
                                uiState.searchQuery.isNotBlank() && uiState.isHydrating ->
                                    "Still loading agents while searching for \"${uiState.searchQuery}\""
                                uiState.searchQuery.isBlank() -> stringResource(R.string.screen_agents_empty)
                                else -> "No agents matching \"${uiState.searchQuery}\""
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        if (showGrid) {
                            val minTileWidth = if (LocalWindowSizeClass.current.isExpandedWidth) 220.dp else 150.dp
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = minTileWidth),
                                contentPadding = PaddingValues(LettaSpacing.screenHorizontal),
                                verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                                horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                            ) {
                                if (uiState.isHydrating) {
                                    item(
                                        key = "agent-hydrating-banner",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        AgentHydratingBanner(loadedCount = uiState.agents.size)
                                    }
                                }

                                if (visibleFavoriteAgent != null) {
                                    item(
                                        key = "favorite-${visibleFavoriteAgent.id}",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        FavoriteAgentCard(
                                            agent = visibleFavoriteAgent,
                                            onClick = { selectAgent(visibleFavoriteAgent.id.value, visibleFavoriteAgent.name) },
                                            onEdit = { onNavigateToEditAgent(visibleFavoriteAgent.id.value) },
                                            onUnfavorite = { viewModel.toggleFavorite(visibleFavoriteAgent.id.value) },
                                            contextualActionsEnabled = !isShareMode,
                                        )
                                    }
                                }

                                items(gridAgents, key = { it.id.value }) { agent ->
                                    CompactAgentCard(
                                        agent = agent,
                                        isFavorite = agent.id.value == uiState.favoriteAgentId,
                                        isPinned = agent.id.value in uiState.pinnedAgentIds,
                                        onClick = { selectAgent(agent.id.value, agent.name) },
                                        onLongPress = { onNavigateToEditAgent(agent.id.value) },
                                        onDelete = { viewModel.deleteAgent(agent.id.value) },
                                        onToggleFavorite = { viewModel.toggleFavorite(agent.id.value) },
                                        onTogglePinned = { viewModel.togglePinned(agent.id.value) },
                                        contextualActionsEnabled = !isShareMode,
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(LettaSpacing.screenHorizontal),
                                verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                            ) {
                                if (uiState.isHydrating) {
                                    item(key = "agent-hydrating-banner") {
                                        AgentHydratingBanner(loadedCount = uiState.agents.size)
                                    }
                                }

                                if (visibleFavoriteAgent != null) {
                                    item(key = "favorite-${visibleFavoriteAgent.id}") {
                                        FavoriteAgentCard(
                                            agent = visibleFavoriteAgent,
                                            onClick = { selectAgent(visibleFavoriteAgent.id.value, visibleFavoriteAgent.name) },
                                            onEdit = { onNavigateToEditAgent(visibleFavoriteAgent.id.value) },
                                            onUnfavorite = { viewModel.toggleFavorite(visibleFavoriteAgent.id.value) },
                                            contextualActionsEnabled = !isShareMode,
                                        )
                                    }
                                }

                                lazyItemsIndexed(
                                    items = gridAgents,
                                    key = { _, agent -> agent.id.value },
                                ) { index, agent ->
                                    StaggeredListItem(index = index) {
                                        AgentCard(
                                            agent = agent,
                                            isFavorite = agent.id.value == uiState.favoriteAgentId,
                                            isPinned = agent.id.value in uiState.pinnedAgentIds,
                                            onClick = { selectAgent(agent.id.value, agent.name) },
                                            onLongPress = { onNavigateToEditAgent(agent.id.value) },
                                            onDelete = { viewModel.deleteAgent(agent.id.value) },
                                            onToggleFavorite = { viewModel.toggleFavorite(agent.id.value) },
                                            onTogglePinned = { viewModel.togglePinned(agent.id.value) },
                                            contextualActionsEnabled = !isShareMode,
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
                    onNavigateToAgent(agentId, params.name)
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

internal data class AgentListDisplayAgents(
    val visibleFavoriteAgent: Agent?,
    val listAgents: List<Agent>,
)

internal fun resolveAgentListDisplayAgents(
    filteredAgents: List<Agent>,
    favoriteAgent: Agent?,
    pinnedAgentIds: Set<String> = emptySet(),
): AgentListDisplayAgents {
    val filteredAgentIds = filteredAgents.mapTo(mutableSetOf()) { it.id }
    val visibleFavoriteAgent = favoriteAgent?.takeIf { it.id in filteredAgentIds }
    return AgentListDisplayAgents(
        visibleFavoriteAgent = visibleFavoriteAgent,
        listAgents = filteredAgents
            .filter { it.id != visibleFavoriteAgent?.id }
            .mapIndexed { index, agent -> index to agent }
            .sortedWith(
                compareByDescending<Pair<Int, Agent>> { it.second.id.value in pinnedAgentIds }
                    .thenBy { it.first },
            )
            .map { it.second },
    )
}

@Composable
internal fun AgentHydratingBanner(
    loadedCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LettaSpacing.cardGap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(modifier = Modifier.size(18.dp))
            Column {
                Text("Loading more agents", style = MaterialTheme.typography.labelLarge)
                Text(
                    "$loadedCount loaded so far. Search results will update as more agents arrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
