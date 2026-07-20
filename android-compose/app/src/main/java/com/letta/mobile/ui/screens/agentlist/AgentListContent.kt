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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
private fun AgentListHydratingBannerItem(loadedCount: Int) {
    AgentHydratingBanner(loadedCount = loadedCount)
}

@Composable
private fun AgentListFavoriteAgentItem(
    favoriteAgent: Agent,
    isShareMode: Boolean,
    actions: AgentListContentActions,
) {
    FavoriteAgentCard(
        agent = favoriteAgent,
        onClick = { actions.onSelectAgent(favoriteAgent.id.value, favoriteAgent.name) },
        onEdit = { actions.onNavigateToEditAgent(favoriteAgent.id.value) },
        onUnfavorite = { actions.onToggleFavorite(favoriteAgent.id) },
        contextualActionsEnabled = !isShareMode,
    )
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
        contentPadding = agentListContentPadding(paddingValues, layoutDirection),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
    ) {
        agentListSharedPrefixItems(state = state, actions = actions)

        items(state.gridAgents, key = { it.id.value }) { agent ->
            AgentListAgentCard(
                rowModel = state.agentRowModel(agent),
                actions = actions,
                variant = AgentListCardVariant.Compact,
            )
        }
    }
}

@Composable
private fun AgentListListContent(
    params: AgentListContentParams,
    paddingValues: PaddingValues,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        state = listState,
        contentPadding = agentListContentPadding(paddingValues, layoutDirection),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
    ) {
        agentListSharedPrefixItems(state = state, actions = actions)

        lazyItemsIndexed(
            items = params.state.gridAgents,
            key = { _, agent -> agent.id.value },
        ) { index, agent ->
            StaggeredListItem(index = index) {
                AgentListAgentCard(
                    rowModel = state.agentRowModel(agent),
                    actions = actions,
                    variant = AgentListCardVariant.Standard,
                )
            }
        }
    }
}

private sealed interface AgentListPrefixItem {
    val listKey: String

    object Hydrating : AgentListPrefixItem {
        override val listKey = "agent-hydrating-banner"
    }

    data class Favorite(val agent: Agent, val shareMode: Boolean) : AgentListPrefixItem {
        override val listKey get() = "favorite-${agent.id}"
    }
}

private fun agentListPrefixItems(state: AgentListContentState): List<AgentListPrefixItem> =
    buildList {
        if (state.uiState.isHydrating) add(AgentListPrefixItem.Hydrating)
        state.visibleFavoriteAgent?.let {
            add(AgentListPrefixItem.Favorite(agent = it, shareMode = state.isShareMode))
        }
    }

@Composable
private fun AgentListPrefixItemContent(
    spec: AgentListPrefixItem,
    state: AgentListContentState,
    actions: AgentListContentActions,
) {
    when (spec) {
        AgentListPrefixItem.Hydrating -> AgentListHydratingBannerItem(loadedCount = state.uiState.agents.size)
        is AgentListPrefixItem.Favorite -> AgentListFavoriteAgentItem(
            favoriteAgent = spec.agent,
            isShareMode = spec.shareMode,
            actions = actions,
        )
    }
}

private inline fun emitAgentListPrefixItems(
    state: AgentListContentState,
    actions: AgentListContentActions,
    emitItem: (key: String, content: @Composable () -> Unit) -> Unit,
) {
    agentListPrefixItems(state).forEach { spec ->
        emitItem(spec.listKey) {
            AgentListPrefixItemContent(spec, state, actions)
        }
    }
}

private fun LazyGridScope.agentListSharedPrefixItems(
    state: AgentListContentState,
    actions: AgentListContentActions,
) {
    emitAgentListPrefixItems(state, actions) { key, content ->
        item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
    }
}

private fun LazyListScope.agentListSharedPrefixItems(
    state: AgentListContentState,
    actions: AgentListContentActions,
) {
    emitAgentListPrefixItems(state, actions) { key, content ->
        item(key = key) { content() }
    }
}

private enum class AgentListCardVariant { Compact, Standard }

@Composable
private fun AgentListAgentCard(
    rowModel: AgentListAgentRowModel,
    actions: AgentListContentActions,
    variant: AgentListCardVariant,
) {
    val model = rowModel.toBindModel(actions)
    when (variant) {
        AgentListCardVariant.Compact -> CompactAgentCard(model)
        AgentListCardVariant.Standard -> AgentCard(model)
    }
}

private fun AgentListAgentRowModel.toBindModel(actions: AgentListContentActions): AgentCardBindModel =
    AgentCardBindModel(
        agent = agent,
        isFavorite = isFavorite,
        isPinned = isPinned,
        contextualActionsEnabled = contextualActionsEnabled,
        onClick = selectAction(actions),
        onLongPress = editAction(actions),
        onDelete = deleteAction(actions),
        onToggleFavorite = toggleFavoriteAction(actions),
        onTogglePinned = togglePinnedAction(actions),
    )

private data class AgentListAgentRowModel(
    val agent: Agent,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val contextualActionsEnabled: Boolean,
)

private fun AgentListContentState.agentRowModel(agent: Agent): AgentListAgentRowModel =
    AgentListAgentRowModel(
        agent = agent,
        isFavorite = agent.id == uiState.favoriteAgentId,
        isPinned = agent.id in uiState.pinnedAgentIds,
        contextualActionsEnabled = !isShareMode,
    )

private fun AgentListAgentRowModel.selectAction(actions: AgentListContentActions): () -> Unit =
    { actions.onSelectAgent(agent.id.value, agent.name) }

private fun AgentListAgentRowModel.editAction(actions: AgentListContentActions): () -> Unit =
    { actions.onNavigateToEditAgent(agent.id.value) }

private fun AgentListAgentRowModel.deleteAction(actions: AgentListContentActions): () -> Unit =
    { actions.onDeleteAgent(agent.id) }

private fun AgentListAgentRowModel.toggleFavoriteAction(actions: AgentListContentActions): () -> Unit =
    { actions.onToggleFavorite(agent.id) }

private fun AgentListAgentRowModel.togglePinnedAction(actions: AgentListContentActions): () -> Unit =
    { actions.onTogglePinned(agent.id) }

@Composable
private fun agentListContentPadding(
    paddingValues: PaddingValues,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
): PaddingValues = PaddingValues(
    start = paddingValues.calculateStartPadding(layoutDirection) + LettaSpacing.screenHorizontal,
    top = paddingValues.calculateTopPadding() + LettaSpacing.screenHorizontal,
    end = paddingValues.calculateEndPadding(layoutDirection) + LettaSpacing.screenHorizontal,
    bottom = paddingValues.calculateBottomPadding() + LettaSpacing.screenHorizontal,
)

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
