package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.icons.LettaIcons

internal data class ConversationsTopBarState(
    val searchQuery: String,
    val isSearchExpanded: Boolean,
    val activeBackendLabel: String?,
    val showOverflowMenu: Boolean,
    val scrollBehavior: TopAppBarScrollBehavior,
)

internal data class ConversationsTopBarCallbacks(
    val onSearchQueryChange: (String) -> Unit,
    val onSearchExpandedChange: (Boolean) -> Unit,
    val onNavigateToBackendSwitcher: (() -> Unit)?,
    val onNavigateToSettings: () -> Unit,
    val onShowOverflowMenuChange: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsTopBar(
    state: ConversationsTopBarState,
    callbacks: ConversationsTopBarCallbacks,
    navigation: ConversationsNavigation,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                ExpandableTitleSearch(
                    query = state.searchQuery,
                    onQueryChange = callbacks.onSearchQueryChange,
                    onClear = { callbacks.onSearchQueryChange("") },
                    expanded = state.isSearchExpanded,
                    onExpandedChange = callbacks.onSearchExpandedChange,
                    placeholder = stringResource(R.string.screen_conversations_search_hint),
                    autoFocus = false,
                    showCollapseButton = false,
                    titleContent = {
                        ConversationsTopBarTitle(
                            activeBackendLabel = state.activeBackendLabel,
                            onNavigateToBackendSwitcher = callbacks.onNavigateToBackendSwitcher,
                        )
                    },
                )
            },
            scrollBehavior = state.scrollBehavior,
            colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.topAppBarColors(),
            actions = {
                IconButton(onClick = callbacks.onNavigateToSettings) {
                    Icon(LettaIcons.Settings, stringResource(R.string.common_settings))
                }
                ConversationsOverflowMenu(
                    expanded = state.showOverflowMenu,
                    onDismiss = { callbacks.onShowOverflowMenuChange(false) },
                    navigation = navigation,
                )
            },
        )
        ExpandableSearchField(
            query = state.searchQuery,
            onQueryChange = callbacks.onSearchQueryChange,
            onClear = { callbacks.onSearchQueryChange("") },
            expanded = state.isSearchExpanded,
            placeholder = stringResource(R.string.screen_conversations_search_hint),
            autoFocus = false,
        )
    }
}

@Composable
private fun ConversationsTopBarTitle(
    activeBackendLabel: String?,
    onNavigateToBackendSwitcher: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.common_conversations))
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
}

private data class ConversationsOverflowMenuItem(
    val labelResId: Int,
    val icon: ImageVector,
    val onNavigate: () -> Unit,
)

@Composable
private fun ConversationsOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    navigation: ConversationsNavigation,
) {
    val menuItems = conversationsOverflowMenuItems(navigation)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        menuItems.forEach { item ->
            DropdownMenuItem(
                text = { Text(stringResource(item.labelResId)) },
                onClick = { onDismiss(); item.onNavigate() },
                leadingIcon = { Icon(item.icon, contentDescription = null) },
            )
        }
    }
}

private fun conversationsOverflowMenuItems(navigation: ConversationsNavigation): List<ConversationsOverflowMenuItem> {
    return listOf(
        ConversationsOverflowMenuItem(R.string.screen_nav_templates, LettaIcons.Dashboard) { navigation.onNavigateToTemplates() },
        ConversationsOverflowMenuItem(R.string.screen_nav_archives, LettaIcons.Storage) { navigation.onNavigateToArchives() },
        ConversationsOverflowMenuItem(R.string.screen_nav_folders, LettaIcons.ManageSearch) { navigation.onNavigateToFolders() },
        ConversationsOverflowMenuItem(R.string.screen_nav_groups, LettaIcons.ForkRight) { navigation.onNavigateToGroups() },
        ConversationsOverflowMenuItem(R.string.screen_nav_providers, LettaIcons.Cloud) { navigation.onNavigateToProviders() },
        ConversationsOverflowMenuItem(R.string.screen_nav_blocks, LettaIcons.Storage) { navigation.onNavigateToBlocks() },
        ConversationsOverflowMenuItem(R.string.screen_nav_identities, LettaIcons.AccountCircle) { navigation.onNavigateToIdentities() },
        ConversationsOverflowMenuItem(R.string.screen_nav_schedules, LettaIcons.AccessTime) { navigation.onNavigateToSchedules() },
        ConversationsOverflowMenuItem(R.string.screen_nav_runs, LettaIcons.ChatOutline) { navigation.onNavigateToRuns() },
        ConversationsOverflowMenuItem(R.string.screen_nav_jobs, LettaIcons.AccessTime) { navigation.onNavigateToJobs() },
        ConversationsOverflowMenuItem(R.string.screen_nav_message_batches, LettaIcons.ChatOutline) { navigation.onNavigateToMessageBatches() },
        ConversationsOverflowMenuItem(R.string.screen_nav_mcp_servers, LettaIcons.Cloud) { navigation.onNavigateToMcp() },
        ConversationsOverflowMenuItem(R.string.screen_projects_title, LettaIcons.Apps) { navigation.onNavigateToProjects() },
        ConversationsOverflowMenuItem(R.string.screen_about_title, LettaIcons.Info) { navigation.onNavigateToAbout() },
    )
}
