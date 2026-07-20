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
import androidx.compose.material3.TopAppBarDefaults
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    activeBackendLabel: String?,
    onNavigateToBackendSwitcher: (() -> Unit)?,
    onNavigateToSettings: () -> Unit,
    navigation: ConversationsNavigation,
    showOverflowMenu: Boolean,
    onShowOverflowMenuChange: (Boolean) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                ExpandableTitleSearch(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = { onSearchQueryChange("") },
                    expanded = isSearchExpanded,
                    onExpandedChange = onSearchExpandedChange,
                    placeholder = stringResource(R.string.screen_conversations_search_hint),
                    autoFocus = false,
                    showCollapseButton = false,
                    titleContent = {
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
                    },
                )
            },
            scrollBehavior = scrollBehavior,
            colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.topAppBarColors(),
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(LettaIcons.Settings, stringResource(R.string.common_settings))
                }
                ConversationsOverflowMenu(
                    expanded = showOverflowMenu,
                    onDismiss = { onShowOverflowMenuChange(false) },
                    navigation = navigation,
                )
            },
        )
        ExpandableSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClear = { onSearchQueryChange("") },
            expanded = isSearchExpanded,
            placeholder = stringResource(R.string.screen_conversations_search_hint),
            autoFocus = false,
        )
    }
}

@Composable
private fun ConversationsOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    navigation: ConversationsNavigation,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_templates)) },
            onClick = { onDismiss(); navigation.onNavigateToTemplates() },
            leadingIcon = { Icon(LettaIcons.Dashboard, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_archives)) },
            onClick = { onDismiss(); navigation.onNavigateToArchives() },
            leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_folders)) },
            onClick = { onDismiss(); navigation.onNavigateToFolders() },
            leadingIcon = { Icon(LettaIcons.ManageSearch, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_groups)) },
            onClick = { onDismiss(); navigation.onNavigateToGroups() },
            leadingIcon = { Icon(LettaIcons.ForkRight, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_providers)) },
            onClick = { onDismiss(); navigation.onNavigateToProviders() },
            leadingIcon = { Icon(LettaIcons.Cloud, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_blocks)) },
            onClick = { onDismiss(); navigation.onNavigateToBlocks() },
            leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_identities)) },
            onClick = { onDismiss(); navigation.onNavigateToIdentities() },
            leadingIcon = { Icon(LettaIcons.AccountCircle, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_schedules)) },
            onClick = { onDismiss(); navigation.onNavigateToSchedules() },
            leadingIcon = { Icon(LettaIcons.AccessTime, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_runs)) },
            onClick = { onDismiss(); navigation.onNavigateToRuns() },
            leadingIcon = { Icon(LettaIcons.ChatOutline, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_jobs)) },
            onClick = { onDismiss(); navigation.onNavigateToJobs() },
            leadingIcon = { Icon(LettaIcons.AccessTime, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_message_batches)) },
            onClick = { onDismiss(); navigation.onNavigateToMessageBatches() },
            leadingIcon = { Icon(LettaIcons.ChatOutline, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_nav_mcp_servers)) },
            onClick = { onDismiss(); navigation.onNavigateToMcp() },
            leadingIcon = { Icon(LettaIcons.Cloud, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_projects_title)) },
            onClick = { onDismiss(); navigation.onNavigateToProjects() },
            leadingIcon = { Icon(LettaIcons.Apps, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.screen_about_title)) },
            onClick = { onDismiss(); navigation.onNavigateToAbout() },
            leadingIcon = { Icon(LettaIcons.Info, contentDescription = null) },
        )
    }
}
