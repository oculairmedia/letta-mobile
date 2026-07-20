package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenTopBar(
    title: String,
    state: DashboardUiState,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClear: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    activeBackendLabel: String?,
    onNavigateToBackendSwitcher: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LargeFlexibleTopAppBar(
            title = {
                ExpandableTitleSearch(
                    query = state.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = onSearchClear,
                    expanded = isSearchExpanded,
                    onExpandedChange = onSearchExpandedChange,
                    placeholder = stringResource(R.string.screen_home_search_placeholder),
                    openSearchContentDescription = stringResource(R.string.action_search),
                    closeSearchContentDescription = stringResource(R.string.action_close),
                    titleContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title)
                            if (state.isConnected) {
                                Icon(
                                    LettaIcons.Circle,
                                    contentDescription = "Connected",
                                    tint = MaterialTheme.customColors.onlineColor,
                                    modifier = Modifier.size(8.dp),
                                )
                            }
                            if (activeBackendLabel != null && onNavigateToBackendSwitcher != null) {
                                AssistChip(
                                    onClick = onNavigateToBackendSwitcher,
                                    label = {
                                        Text(
                                            activeBackendLabel,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(LettaIcons.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(LettaIcons.Settings, contentDescription = "Settings")
                }
            },
            colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
            scrollBehavior = scrollBehavior,
        )
        ExpandableSearchField(
            query = state.searchQuery,
            onQueryChange = onSearchQueryChange,
            onClear = onSearchClear,
            expanded = isSearchExpanded,
            placeholder = stringResource(R.string.screen_home_search_placeholder),
        )
    }
}
