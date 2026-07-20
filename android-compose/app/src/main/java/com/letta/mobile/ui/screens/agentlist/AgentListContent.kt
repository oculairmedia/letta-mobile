package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerGrid
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.StaggeredListItem
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth

internal data class AgentListContentState(
    val uiState: AgentListUiState,
    val isShareMode: Boolean,
    val filteredAgents: List<Agent>,
    val visibleFavoriteAgent: Agent?,
    val gridAgents: List<Agent>,
)

internal data class AgentListContentActions(
    val onSelectAgent: (String, String?) -> Unit,
    val onNavigateToEditAgent: (String) -> Unit,
    val onDeleteAgent: (AgentId) -> Unit,
    val onToggleFavorite: (AgentId) -> Unit,
    val onTogglePinned: (AgentId) -> Unit,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit,
    val onCreateAgent: () -> Unit,
)

internal data class AgentListContentLayout(
    val paddingValues: PaddingValues,
    val listState: LazyListState,
    val gridState: LazyGridState,
    val haptic: HapticFeedback,
)

@Composable
internal fun AgentListContent(
    state: AgentListContentState,
    actions: AgentListContentActions,
    layout: AgentListContentLayout,
) {
    val uiState = params.state.uiState
    val agentError = uiState.error
    when {
        uiState.isLoading -> ShimmerGrid(modifier = Modifier.padding(layout.paddingValues))
        agentError != null && uiState.agents.isEmpty() -> ErrorContent(
            message = agentError,
            onRetry = actions.onRetry,
            modifier = Modifier.padding(layout.paddingValues),
        )
        else -> AgentListRefreshableContent(
            state = state,
            actions = actions,
            layout = layout,
        )
    }
}

@Composable
private fun AgentListRefreshableContent(
    state: AgentListContentState,
    actions: AgentListContentActions,
    layout: AgentListContentLayout,
) {
    val uiState = params.state.uiState
    val view = LocalView.current
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = {
            HapticEffects.confirm(layout.haptic, view)
            actions.onRefresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        AgentListBody(
            state = state,
            actions = actions,
            layout = layout,
        )
    }
}

@Composable
private fun AgentListBody(
    state: AgentListContentState,
    actions: AgentListContentActions,
    layout: AgentListContentLayout,
) {
    val uiState = state.uiState
    if (state.filteredAgents.isEmpty()) {
        AgentListEmptyState(
            message = agentListEmptyMessage(uiState),
            showCreateAction = shouldShowEmptyAgentCreateAction(
                isShareMode = state.isShareMode,
                isHydrating = uiState.isHydrating,
                searchQuery = uiState.searchQuery,
            ),
            onCreateAgent = actions.onCreateAgent,
            modifier = Modifier
                .padding(layout.paddingValues)
                .fillMaxSize(),
        )
        return
    }

    if (uiState.showGrid) {
        AgentListGridContent(
            state = state,
            actions = actions,
            paddingValues = layout.paddingValues,
            gridState = layout.gridState,
        )
    } else {
        AgentListListContent(
            state = state,
            actions = actions,
            paddingValues = layout.paddingValues,
            listState = layout.listState,
        )
    }
}

@Composable
private fun agentListEmptyMessage(uiState: AgentListUiState): String {
    if (uiState.searchQuery.isNotBlank() && uiState.isHydrating) {
        return "Still loading agents while searching for \"${uiState.searchQuery}\""
    }
    if (uiState.searchQuery.isBlank()) {
        return stringResource(R.string.screen_agents_empty)
    }
    return "No agents matching \"${uiState.searchQuery}\""
}

@Composable
private fun AgentListGridContent(
    params: AgentListContentParams,
    paddingValues: PaddingValues,
) {
    val uiState = params.state.uiState
    val layoutDirection = LocalLayoutDirection.current
    val minTileWidth = if (LocalWindowSizeClass.current.isExpandedWidth) 220.dp else 150.dp
    LazyVerticalGrid(
        state = params.gridState,
        columns = GridCells.Adaptive(minSize = minTileWidth),
        contentPadding = PaddingValues(
            start = paddingValues.calculateStartPadding(layoutDirection) + LettaSpacing.SCREEN_HORIZONTAL,
            top = paddingValues.calculateTopPadding() + LettaSpacing.SCREEN_HORIZONTAL,
            end = paddingValues.calculateEndPadding(layoutDirection) + LettaSpacing.SCREEN_HORIZONTAL,
            bottom = paddingValues.calculateBottomPadding() + LettaSpacing.SCREEN_HORIZONTAL,
        ),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.CARD_GAP),
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.CARD_GAP),
    ) {
        if (uiState.isHydrating) {
            item(
                key = "agent-hydrating-banner",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                AgentHydratingBanner(loadedCount = uiState.agents.size)
            }
        }

        params.state.visibleFavoriteAgent?.let { favoriteAgent ->
            item(
                key = "favorite-${favoriteAgent.id}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FavoriteAgentCard(
                    agent = favoriteAgent,
                    onClick = { params.actions.onSelectAgent(favoriteAgent.id.value, favoriteAgent.name) },
                    onEdit = { params.actions.onNavigateToEditAgent(favoriteAgent.id.value) },
                    onUnfavorite = { params.actions.onToggleFavorite(favoriteAgent.id) },
                    contextualActionsEnabled = !params.state.isShareMode,
                )
            }
        }

        items(params.state.gridAgents, key = { it.id.value }) { agent ->
            CompactAgentCard(
                agent = agent,
                isFavorite = agent.id == uiState.favoriteAgentId,
                isPinned = agent.id in uiState.pinnedAgentIds,
                onClick = { params.actions.onSelectAgent(agent.id.value, agent.name) },
                onLongPress = { params.actions.onNavigateToEditAgent(agent.id.value) },
                onDelete = { params.actions.onDeleteAgent(agent.id) },
                onToggleFavorite = { params.actions.onToggleFavorite(agent.id) },
                onTogglePinned = { params.actions.onTogglePinned(agent.id) },
                contextualActionsEnabled = !params.state.isShareMode,
            )
        }
    }
}

@Composable
private fun AgentListListContent(
    params: AgentListContentParams,
    paddingValues: PaddingValues,
) {
    val uiState = params.state.uiState
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        state = params.listState,
        contentPadding = PaddingValues(
            start = paddingValues.calculateStartPadding(layoutDirection) + LettaSpacing.SCREEN_HORIZONTAL,
            top = paddingValues.calculateTopPadding() + LettaSpacing.SCREEN_HORIZONTAL,
            end = paddingValues.calculateEndPadding(layoutDirection) + LettaSpacing.SCREEN_HORIZONTAL,
            bottom = paddingValues.calculateBottomPadding() + LettaSpacing.SCREEN_HORIZONTAL,
        ),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.CARD_GAP),
    ) {
        if (uiState.isHydrating) {
            item(key = "agent-hydrating-banner") {
                AgentHydratingBanner(loadedCount = uiState.agents.size)
            }
        }

        params.state.visibleFavoriteAgent?.let { favoriteAgent ->
            item(key = "favorite-${favoriteAgent.id}") {
                FavoriteAgentCard(
                    agent = favoriteAgent,
                    onClick = { params.actions.onSelectAgent(favoriteAgent.id.value, favoriteAgent.name) },
                    onEdit = { params.actions.onNavigateToEditAgent(favoriteAgent.id.value) },
                    onUnfavorite = { params.actions.onToggleFavorite(favoriteAgent.id) },
                    contextualActionsEnabled = !params.state.isShareMode,
                )
            }
        }

        lazyItemsIndexed(
            items = params.state.gridAgents,
            key = { _, agent -> agent.id.value },
        ) { index, agent ->
            StaggeredListItem(index = index) {
                AgentCard(
                    agent = agent,
                    isFavorite = agent.id == uiState.favoriteAgentId,
                    isPinned = agent.id in uiState.pinnedAgentIds,
                    onClick = { params.actions.onSelectAgent(agent.id.value, agent.name) },
                    onLongPress = { params.actions.onNavigateToEditAgent(agent.id.value) },
                    onDelete = { params.actions.onDeleteAgent(agent.id) },
                    onToggleFavorite = { params.actions.onToggleFavorite(agent.id) },
                    onTogglePinned = { params.actions.onTogglePinned(agent.id) },
                    contextualActionsEnabled = !params.state.isShareMode,
                )
            }
        }
    }
}

@Composable
private fun AgentListEmptyState(
    message: String,
    showCreateAction: Boolean,
    onCreateAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = LettaIcons.Agent,
        message = message,
        modifier = modifier,
        actionLabel = if (showCreateAction) stringResource(R.string.screen_agents_empty_create_action) else null,
        actionIcon = if (showCreateAction) LettaIcons.Add else null,
        onAction = if (showCreateAction) onCreateAgent else null,
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
            .padding(bottom = LettaSpacing.CARD_GAP),
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
