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

internal data class HomeScreenTopBarParams(
    val title: String,
    val state: DashboardUiState,
    val isSearchExpanded: Boolean,
    val onSearchExpandedChange: (Boolean) -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onSearchClear: () -> Unit,
    val onOpenDrawer: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val activeBackendLabel: String?,
    val onNavigateToBackendSwitcher: (() -> Unit)?,
    val scrollBehavior: TopAppBarScrollBehavior,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenTopBar(params: HomeScreenTopBarParams) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HomeScreenTopBarAppBar(params)
        HomeScreenTopBarSearchField(params)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenTopBarAppBar(params: HomeScreenTopBarParams) {
    LargeFlexibleTopAppBar(
        title = { HomeScreenTopBarTitle(params) },
        navigationIcon = {
            IconButton(onClick = params.onOpenDrawer) {
                Icon(LettaIcons.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            IconButton(onClick = params.onNavigateToSettings) {
                Icon(LettaIcons.Settings, contentDescription = "Settings")
            }
        },
        colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
        scrollBehavior = params.scrollBehavior,
    )
}

@Composable
private fun HomeScreenTopBarTitle(params: HomeScreenTopBarParams) {
    ExpandableTitleSearch(
        query = params.state.searchQuery,
        onQueryChange = params.onSearchQueryChange,
        onClear = params.onSearchClear,
        expanded = params.isSearchExpanded,
        onExpandedChange = params.onSearchExpandedChange,
        placeholder = stringResource(R.string.screen_home_search_placeholder),
        openSearchContentDescription = stringResource(R.string.action_search),
        closeSearchContentDescription = stringResource(R.string.action_close),
        titleContent = { HomeScreenTopBarTitleRow(params) },
    )
}

@Composable
private fun HomeScreenTopBarTitleRow(params: HomeScreenTopBarParams) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(params.title)
        if (params.state.isConnected) {
            Icon(
                LettaIcons.Circle,
                contentDescription = "Connected",
                tint = MaterialTheme.customColors.onlineColor,
                modifier = Modifier.size(8.dp),
            )
        }
        HomeScreenTopBarBackendChip(params)
    }
}

@Composable
private fun HomeScreenTopBarBackendChip(params: HomeScreenTopBarParams) {
    val label = params.activeBackendLabel ?: return
    val onNavigate = params.onNavigateToBackendSwitcher ?: return
    AssistChip(
        onClick = onNavigate,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun HomeScreenTopBarSearchField(params: HomeScreenTopBarParams) {
    ExpandableSearchField(
        query = params.state.searchQuery,
        onQueryChange = params.onSearchQueryChange,
        onClear = params.onSearchClear,
        expanded = params.isSearchExpanded,
        placeholder = stringResource(R.string.screen_home_search_placeholder),
    )
}
