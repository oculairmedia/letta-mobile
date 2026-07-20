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

/**
 * Everything [AgentListTopBar] needs to draw. Keeping this as one holder is
 * what lets the top-level composable stay at two arguments and clear the
 * CodeScene "Excess Number of Function Arguments" advisory.
 */
internal data class AgentListTopBarParams(
    val uiState: AgentListUiState,
    val isShareMode: Boolean,
    val shareContentPreview: String?,
    val scrollBehavior: TopAppBarScrollBehavior,
    val actions: AgentListTopBarActions,
    val haptic: HapticFeedback,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentListTopBar(params: AgentListTopBarParams) {
    val haptic = params.haptic
    val view = LocalView.current
    Column(modifier = Modifier.fillMaxWidth()) {
        AgentListLargeTopAppBar(params = params)
        ExpandableSearchField(
            query = params.uiState.searchQuery,
            onQueryChange = params.actions.onUpdateSearchQuery,
            onClear = { params.actions.onUpdateSearchQuery("") },
            expanded = params.uiState.isSearchExpanded,
            placeholder = stringResource(R.string.screen_agents_search_hint),
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SegmentedButton(
                selected = !params.uiState.showGrid,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = params.uiState.showGrid)
                    params.actions.onSetShowGrid(false)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(stringResource(R.string.screen_agents_view_list)) },
            )
            SegmentedButton(
                selected = params.uiState.showGrid,
                onClick = {
                    HapticEffects.segmentTick(haptic, view, enabled = !params.uiState.showGrid)
                    params.actions.onSetShowGrid(true)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(stringResource(R.string.screen_agents_view_grid)) },
            )
        }

        val allTags = remember(params.uiState.agents) { params.actions.getAllTags() }
        if (params.shareContentPreview != null) {
            ShareContentPreviewCard(
                content = params.shareContentPreview,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (allTags.isNotEmpty()) {
            AgentListTagFilterRow(
                params = AgentListTagFilterRowParams(
                    allTags = allTags,
                    selectedTags = params.uiState.selectedTags,
                    onClearTags = params.actions.onClearTags,
                    onToggleTag = params.actions.onToggleTag,
                    haptic = haptic,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListLargeTopAppBar(params: AgentListTopBarParams) {
    LargeFlexibleTopAppBar(
        title = {
            ExpandableTitleSearch(
                query = params.uiState.searchQuery,
                onQueryChange = params.actions.onUpdateSearchQuery,
                onClear = { params.actions.onUpdateSearchQuery("") },
                expanded = params.uiState.isSearchExpanded,
                onExpandedChange = params.actions.onSetSearchExpanded,
                placeholder = stringResource(R.string.screen_agents_search_hint),
                openSearchContentDescription = stringResource(R.string.action_search),
                closeSearchContentDescription = stringResource(R.string.action_close),
                titleContent = {
                    Text(
                        if (params.isShareMode) {
                            "Share with agent"
                        } else {
                            stringResource(R.string.common_agents)
                        },
                    )
                },
            )
        },
        scrollBehavior = params.scrollBehavior,
        colors = LettaTopBarDefaults.largeTopAppBarColors(),
        navigationIcon = {
            IconButton(onClick = params.actions.onNavigateBack) {
                Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            if (!params.isShareMode) {
                IconButton(onClick = params.actions.onShowImportDialog) {
                    Icon(
                        LettaIcons.FileOpen,
                        contentDescription = stringResource(R.string.action_import_agent),
                    )
                }
            }
        },
    )
}

private data class AgentListTagFilterRowParams(
    val allTags: List<String>,
    val selectedTags: Set<String>,
    val onClearTags: () -> Unit,
    val onToggleTag: (String) -> Unit,
    val haptic: HapticFeedback,
)

@Composable
private fun AgentListTagFilterRow(params: AgentListTagFilterRowParams) {
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
                selected = params.selectedTags.isEmpty(),
                onClick = {
                    HapticEffects.segmentTick(params.haptic, view, enabled = params.selectedTags.isNotEmpty())
                    params.onClearTags()
                },
                label = { Text(stringResource(R.string.screen_agents_filter_all)) },
            )
        }
        items(params.allTags.size, key = { params.allTags[it] }) { index ->
            val tag = params.allTags[index]
            FilterChip(
                selected = tag in params.selectedTags,
                onClick = {
                    HapticEffects.segmentTick(params.haptic, view)
                    params.onToggleTag(tag)
                },
                label = { Text(tag) },
            )
        }
    }
}
