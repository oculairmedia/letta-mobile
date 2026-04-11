package com.letta.mobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerGrid
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllToolsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToToolDetail: (String) -> Unit = {},
    viewModel: AllToolsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, contentDescription = stringResource(R.string.screen_tools_create_action))
            }
        },
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.common_tools)) },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
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
                    val searchQuery = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty()
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.screen_tools_search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(LettaIcons.Clear, contentDescription = stringResource(R.string.action_cancel))
                                }
                            }
                        },
                    )
                }

                val allToolTags = remember(uiState) {
                    viewModel.getAllTags()
                }
                if (allToolTags.isNotEmpty()) {
                    val selectedToolTags = (uiState as? UiState.Success)?.data?.selectedTags.orEmpty()
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedToolTags.isEmpty(),
                                onClick = { viewModel.clearTags() },
                                label = { Text(stringResource(R.string.screen_tools_filter_all)) },
                            )
                        }
                        items(allToolTags.size) { index ->
                            val tag = allToolTags[index]
                            FilterChip(
                                selected = tag in selectedToolTags,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerGrid(modifier = Modifier.padding(paddingValues))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadTools() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredTools = remember(state.data.tools, state.data.searchQuery, state.data.selectedTags) {
                    viewModel.getFilteredTools()
                }
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.loadTools() },
                    modifier = Modifier.padding(paddingValues).fillMaxSize(),
                ) {
                    if (filteredTools.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Tool,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_tools_empty)
                            } else {
                                stringResource(R.string.screen_tools_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredTools, key = { it.id }) { tool ->
                                ToolTile(
                                    tool = tool,
                                    onClick = { onNavigateToToolDetail(tool.id) },
                                )
                            }

                            if (state.data.hasMorePages && state.data.searchQuery.isBlank()) {
                                item(span = { GridItemSpan(3) }) {
                                    LaunchedEffect(Unit) {
                                        viewModel.loadMoreTools()
                                    }
                                    if (state.data.isLoadingMore) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp,
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

    if (showCreateDialog) {
        CreateToolDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { sourceCode ->
                viewModel.createTool(sourceCode)
                showCreateDialog = false
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ToolTile(
    tool: Tool,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(100.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            Icon(
                imageVector = LettaIcons.Tool,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .optionalSharedElement("tool_icon_${tool.id}"),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tool.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
