package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.statefulFadingEdges
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults

internal data class AgentListTopBarActions(
    val onNavigateBack: () -> Unit,
    val onShowImportDialog: () -> Unit,
    val onUpdateSearchQuery: (String) -> Unit,
    val onSetSearchExpanded: (Boolean) -> Unit,
    val onSetShowGrid: (Boolean) -> Unit,
    val onClearTags: () -> Unit,
    val onToggleTag: (String) -> Unit,
    val getAllTags: () -> List<String>,
)

internal data class AgentListTopBarState(
    val uiState: AgentListUiState,
    val isShareMode: Boolean,
    val shareContentPreview: String?,
    val scrollBehavior: TopAppBarScrollBehavior,
)

internal data class AgentListTagFilterState(
    val allTags: List<String>,
    val selectedTags: Set<String>,
)

internal data class AgentListTagFilterActions(
    val onClearTags: () -> Unit,
    val onToggleTag: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentListTopBar(
    state: AgentListTopBarState,
    actions: AgentListTopBarActions,
    haptic: HapticFeedback,
) {
    val uiState = state.uiState
    Column(modifier = Modifier.fillMaxWidth()) {
        AgentListTopAppBar(
            state = state,
            actions = actions,
        )
        ExpandableSearchField(
            query = uiState.searchQuery,
            onQueryChange = actions.onUpdateSearchQuery,
            onClear = { actions.onUpdateSearchQuery("") },
            expanded = uiState.isSearchExpanded,
            placeholder = stringResource(R.string.screen_agents_search_hint),
        )
        AgentListViewModeToggle(
            showGrid = uiState.showGrid,
            onSetShowGrid = actions.onSetShowGrid,
            haptic = haptic,
        )
        AgentListTopBarTagSection(
            uiState = uiState,
            shareContentPreview = state.shareContentPreview,
            actions = actions,
            haptic = haptic,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListTopAppBar(
    state: AgentListTopBarState,
    actions: AgentListTopBarActions,
) {
    val uiState = state.uiState
    LargeFlexibleTopAppBar(
        title = {
            ExpandableTitleSearch(
                query = uiState.searchQuery,
                onQueryChange = actions.onUpdateSearchQuery,
                onClear = { actions.onUpdateSearchQuery("") },
                expanded = uiState.isSearchExpanded,
                onExpandedChange = actions.onSetSearchExpanded,
                placeholder = stringResource(R.string.screen_agents_search_hint),
                openSearchContentDescription = stringResource(R.string.action_search),
                closeSearchContentDescription = stringResource(R.string.action_close),
                titleContent = {
                    Text(if (state.isShareMode) "Share with agent" else stringResource(R.string.common_agents))
                },
            )
        },
        scrollBehavior = state.scrollBehavior,
        colors = LettaTopBarDefaults.largeTopAppBarColors(),
        navigationIcon = {
            IconButton(onClick = actions.onNavigateBack) {
                Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            if (!state.isShareMode) {
                IconButton(onClick = actions.onShowImportDialog) {
                    Icon(
                        LettaIcons.FileOpen,
                        contentDescription = stringResource(R.string.action_import_agent),
                    )
                }
            }
        },
    )
}

@Composable
private fun AgentListViewModeToggle(
    showGrid: Boolean,
    onSetShowGrid: (Boolean) -> Unit,
    haptic: HapticFeedback,
) {
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        SegmentedButton(
            selected = !showGrid,
            onClick = {
                HapticEffects.segmentTick(haptic, view, enabled = showGrid)
                onSetShowGrid(false)
            },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.screen_agents_view_list)) },
        )
        SegmentedButton(
            selected = showGrid,
            onClick = {
                HapticEffects.segmentTick(haptic, view, enabled = !showGrid)
                onSetShowGrid(true)
            },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.screen_agents_view_grid)) },
        )
    }
}

@Composable
private fun AgentListTopBarTagSection(
    uiState: AgentListUiState,
    shareContentPreview: String?,
    actions: AgentListTopBarActions,
    haptic: HapticFeedback,
) {
    val allTags = remember(uiState.agents) { actions.getAllTags() }
    if (shareContentPreview != null) {
        ShareContentPreviewCard(
            content = shareContentPreview,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (allTags.isNotEmpty()) {
        AgentListTagFilterRow(
            state = AgentListTagFilterState(
                allTags = allTags,
                selectedTags = uiState.selectedTags,
            ),
            actions = AgentListTagFilterActions(
                onClearTags = actions.onClearTags,
                onToggleTag = actions.onToggleTag,
            ),
            haptic = haptic,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListTagFilterRow(
    state: AgentListTagFilterState,
    actions: AgentListTagFilterActions,
    haptic: HapticFeedback,
) {
    val view = LocalView.current
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
                selected = state.selectedTags.isEmpty(),
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = state.selectedTags.isNotEmpty())
                    actions.onClearTags()
                },
                label = { Text(stringResource(R.string.screen_agents_filter_all)) },
            )
        }
        items(state.allTags.size, key = { state.allTags[it] }) { index ->
            val tag = state.allTags[index]
            FilterChip(
                selected = tag in state.selectedTags,
                onClick = {
                    HapticEffects.segmentTick(haptic, view)
                    actions.onToggleTag(tag)
                },
                label = { Text(tag) },
            )
        }
    }
}
