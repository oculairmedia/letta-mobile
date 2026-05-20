package com.letta.mobile.ui.screens.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.PmAgentMetadata
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.feature.chat.ProjectChatStartAction
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import com.letta.mobile.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(
    onNavigateBack: (() -> Unit)?,
    onNavigateToProjectChat: (project: ProjectSummary, projectStartAction: String?) -> Unit,
    onNavigateToPmAgentChat: (agentId: String) -> Unit = {},
    onNavigateToProjectIssues: (project: ProjectSummary) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCreateProject: () -> Unit,
    activeBackendLabel: String? = null,
    onNavigateToBackendSwitcher: (() -> Unit)? = null,
    viewModel: ProjectHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var isAppBarCollapsed by remember { mutableStateOf(false) }

    LaunchedEffect(scrollBehavior) {
        snapshotFlow { scrollBehavior.state.collapsedFraction }
            .collect { fraction ->
                isAppBarCollapsed = fraction >= 0.9f
            }
    }
    val missingAgentMessage = stringResource(R.string.screen_projects_missing_agent, "%s")
    val projectSettingsTitle = stringResource(R.string.screen_projects_settings_title)
    val projectSettingsPathLabel = stringResource(R.string.screen_projects_settings_path_label)
    val projectSettingsGitUrlLabel = stringResource(R.string.screen_projects_settings_git_url_label)
    val projectSettingsPathMissing = stringResource(R.string.screen_projects_settings_path_missing)
    val projectSettingsPathAbsolute = stringResource(R.string.screen_projects_settings_path_must_be_absolute)
    val projectSettingsPathKnown = stringResource(R.string.screen_projects_settings_path_known_project)
    val projectSettingsPathUnknown = stringResource(R.string.screen_projects_settings_path_unknown_access)
    val projectSettingsSuggestedTitle = stringResource(R.string.screen_projects_settings_suggested_paths_title)

    // letta-mobile-cygd: refresh on return from CreateProjectRoute. The
    // create screen sets PROJECT_CREATED_REFRESH_KEY=true on the previous
    // entry's saved-state handle when it pops on success; we observe
    // that flag here, refresh, and clear it. The hilt-injected VM is
    // scoped to the hosting NavBackStackEntry, so the same entry is
    // also a SavedStateRegistryOwner — pull the SavedStateHandle from
    // it via LocalViewModelStoreOwner.
    val viewModelStoreOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
    val savedStateHandle = (viewModelStoreOwner as? androidx.navigation.NavBackStackEntry)?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle ?: return@LaunchedEffect
        savedStateHandle
            .getStateFlow(com.letta.mobile.ui.navigation.PROJECT_CREATED_REFRESH_KEY, false)
            .collect { needsRefresh ->
                if (needsRefresh) {
                    savedStateHandle[com.letta.mobile.ui.navigation.PROJECT_CREATED_REFRESH_KEY] = false
                    viewModel.refresh()
                }
            }
    }

    var showOverflow by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            val searchQuery = (uiState as? UiState.Success)?.data?.searchQuery ?: ""
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_projects_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        autoFocus = false,
                        isAppBarCollapsed = isAppBarCollapsed,
                        titleContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.screen_projects_title))
                                if (activeBackendLabel != null && onNavigateToBackendSwitcher != null) {
                                    AssistChip(
                                        onClick = onNavigateToBackendSwitcher,
                                        label = { Text(activeBackendLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                navigationIcon = {
                    onNavigateBack?.let { navigateBack ->
                        IconButton(onClick = navigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_create_project_overflow_manual)) },
                            leadingIcon = { Icon(LettaIcons.Edit, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                viewModel.startManualProjectCreation()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_settings)) },
                            leadingIcon = { Icon(LettaIcons.Settings, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                onNavigateToSettings()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreateProject,
            ) {
                Icon(LettaIcons.Add, contentDescription = stringResource(R.string.screen_projects_new_project))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                        .padding(LettaSpacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                ) {
                    for (i in 0..5) {
                        ShimmerBox(height = 120.dp, widthFraction = 1f)
                    }
                }
            }

            is UiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            is UiState.Success -> {
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ProjectHomeUiEvent.ShowMessage -> snackbar.dispatch(event.message)
                        }
                    }
                }

                val filteredProjects = remember(state.data.projects, state.data.searchQuery) {
                    if (state.data.searchQuery.isBlank()) {
                        state.data.projects
                    } else {
                        val q = state.data.searchQuery.trim().lowercase()
                        state.data.projects.filter { project ->
                            project.name.lowercase().contains(q) ||
                                project.identifier.lowercase().contains(q) ||
                                project.filesystemPath?.lowercase()?.contains(q) == true
                        }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = state.data.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                ) {
                    if (filteredProjects.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Apps,
                            message = if (state.data.searchQuery.isBlank()) stringResource(R.string.screen_projects_empty)
                            else "No projects matching \"${state.data.searchQuery}\"",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val minTileWidth = if (LocalWindowSizeClass.current.isExpandedWidth) 220.dp else 150.dp
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxSize(),
                            columns = GridCells.Adaptive(minSize = minTileWidth),
                            contentPadding = PaddingValues(LettaSpacing.screenHorizontal),
                            verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                        ) {
                            items(filteredProjects, key = { it.identifier }) { project ->
                                ProjectTile(
                                    project = project,
                                    isPinned = project.identifier in state.data.pinnedProjectIds,
                                    onClick = {
                                        val agentId = project.lettaAgentId
                                        if (agentId == null || agentId.value.isBlank()) {
                                            snackbar.dispatch(missingAgentMessage.format(project.name))
                                        } else {
                                            onNavigateToProjectChat(project, null)
                                        }
                                    },
                                    onOpenActions = { viewModel.selectProject(project.identifier) },
                                )
                            }
                        }
                    }
                }

                val selectedProject = viewModel.currentProject()
                // letta-mobile-cygd: the FAB Manual/Conversational ActionSheet
                // is gone. FAB now navigates directly to CreateProjectRoute
                // (conversational); Manual lives in the top-app-bar overflow
                // and reuses the MultiFieldInputDialog below.

                MultiFieldInputDialog(
                    show = state.data.showManualCreateDialog,
                    title = stringResource(R.string.screen_projects_new_project_manual_title),
                    confirmText = stringResource(R.string.action_create),
                    dismissText = stringResource(R.string.action_cancel),
                    onDismiss = viewModel::dismissManualProjectCreation,
                    confirmEnabled = state.data.newProjectDraft.isReadyToSubmit() && !state.data.isSubmittingManualCreate,
                    onConfirm = viewModel::submitManualProjectCreation,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.data.newProjectDraft.name,
                            onValueChange = { viewModel.updateNewProjectDraft(state.data.newProjectDraft.copy(name = it)) },
                            label = { Text(stringResource(R.string.screen_projects_new_project_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.data.newProjectDraft.description,
                            onValueChange = { viewModel.updateNewProjectDraft(state.data.newProjectDraft.copy(description = it)) },
                            label = { Text(stringResource(R.string.common_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        OutlinedTextField(
                            value = state.data.newProjectDraft.filesystemPath,
                            onValueChange = { viewModel.updateNewProjectDraft(state.data.newProjectDraft.copy(filesystemPath = it)) },
                            label = { Text(stringResource(R.string.screen_projects_new_project_path_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.data.newProjectDraft.gitUrl,
                            onValueChange = { viewModel.updateNewProjectDraft(state.data.newProjectDraft.copy(gitUrl = it)) },
                            label = { Text(stringResource(R.string.screen_projects_new_project_git_url_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                // letta-mobile-cygd: conversational creation dialog moved
                // to its own full-screen route (CreateProjectScreen). The
                // FAB navigates there directly via onNavigateToCreateProject.

                MultiFieldInputDialog(
                    show = state.data.showProjectSettingsDialog,
                    title = projectSettingsTitle,
                    confirmText = stringResource(R.string.action_save),
                    dismissText = stringResource(R.string.action_cancel),
                    onDismiss = viewModel::dismissProjectSettingsEdit,
                    confirmEnabled = state.data.projectSettingsDraft.isReadyToSubmit() && !state.data.isSubmittingProjectSettings,
                    onConfirm = viewModel::submitProjectSettingsEdit,
                ) {
                    val pathSuggestions = remember(state.data.projects) {
                        knownProjectPathSuggestions(state.data.projects)
                    }
                    val pathGuidance = projectSettingsPathGuidance(
                        draft = state.data.projectSettingsDraft,
                        knownProjectPaths = pathSuggestions.toSet(),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = state.data.projectSettingsDraft.projectName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = state.data.projectSettingsDraft.filesystemPath,
                            onValueChange = {
                                viewModel.updateProjectSettingsDraft(
                                    state.data.projectSettingsDraft.copy(filesystemPath = it)
                                )
                            },
                            label = { Text(projectSettingsPathLabel) },
                            supportingText = {
                                Text(
                                    when (pathGuidance) {
                                        ProjectSettingsPathGuidance.Missing -> projectSettingsPathMissing
                                        ProjectSettingsPathGuidance.MustBeAbsolute -> projectSettingsPathAbsolute
                                        ProjectSettingsPathGuidance.KnownProjectPath -> projectSettingsPathKnown
                                        ProjectSettingsPathGuidance.UnknownServerAccess -> projectSettingsPathUnknown
                                    }
                                )
                            },
                            isError = pathGuidance != ProjectSettingsPathGuidance.KnownProjectPath &&
                                pathGuidance != ProjectSettingsPathGuidance.UnknownServerAccess,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (pathSuggestions.isNotEmpty()) {
                            FormItem(
                                label = { Text(projectSettingsSuggestedTitle) },
                                description = { Text(projectSettingsPathKnown) },
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pathSuggestions.forEach { suggestion ->
                                        SuggestionChip(
                                            onClick = {
                                                viewModel.updateProjectSettingsDraft(
                                                    state.data.projectSettingsDraft.copy(filesystemPath = suggestion)
                                                )
                                            },
                                            label = { Text(suggestion) },
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = state.data.projectSettingsDraft.gitUrl,
                            onValueChange = {
                                viewModel.updateProjectSettingsDraft(
                                    state.data.projectSettingsDraft.copy(gitUrl = it)
                                )
                            },
                            label = { Text(projectSettingsGitUrlLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                ActionSheet(
                    show = selectedProject != null,
                    onDismiss = { viewModel.selectProject(null) },
                    title = selectedProject?.name,
                ) {
                    val project = selectedProject ?: return@ActionSheet
                    val isPinned = project.identifier in state.data.pinnedProjectIds
                    val beadsStatus = state.data.beadsRemoteStatusByProject[project.identifier]
                    val pmAgent = state.data.pmAgentByProject[project.identifier]
                    val syncInFlight = state.data.syncingProjectId == project.identifier
                    fun openProjectChat(projectStartAction: String?) {
                        viewModel.selectProject(null)
                        val agentId = project.lettaAgentId
                        if (agentId == null || agentId.value.isBlank()) {
                            snackbar.dispatch(missingAgentMessage.format(project.name))
                        } else {
                            onNavigateToProjectChat(project, projectStartAction)
                        }
                    }

                    ProjectActionSheetHeader(
                        project = project,
                        beadsStatus = beadsStatus,
                        pmAgent = pmAgent,
                        syncInFlight = syncInFlight,
                        onPmAgentClick = {
                            viewModel.selectProject(null)
                            onNavigateToPmAgentChat(it)
                        },
                    )

                    ActionSheetItem(
                        text = if (isPinned) "Unpin from top" else "Pin to top",
                        icon = if (isPinned) LettaIcons.PinOff else LettaIcons.Pin,
                        onClick = viewModel::toggleSelectedProjectPinned,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_active_agents_action),
                        icon = LettaIcons.People,
                        onClick = { openProjectChat(ProjectChatStartAction.ActiveAgents) },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_project_brief_action),
                        icon = LettaIcons.FileOpen,
                        onClick = { openProjectChat(ProjectChatStartAction.ProjectBrief) },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_project_bug_report_open),
                        icon = LettaIcons.Error,
                        onClick = { openProjectChat(ProjectChatStartAction.BugReport) },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_open_chat_action),
                        icon = LettaIcons.Chat,
                        onClick = { openProjectChat(null) },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_open_issues_action),
                        icon = LettaIcons.ListIcon,
                        onClick = {
                            viewModel.selectProject(null)
                            onNavigateToProjectIssues(project)
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_sync_now_action),
                        icon = LettaIcons.Refresh,
                        enabled = !syncInFlight,
                        supportingText = project.lastSyncAt?.let { stringResource(R.string.screen_projects_last_sync_supporting, formatRelativeTime(it)) },
                        onClick = viewModel::triggerSyncNow,
                    )
                    if (beadsStatus?.status != "provisioned") {
                        ActionSheetItem(
                            text = stringResource(R.string.screen_projects_beads_provision_action),
                            icon = LettaIcons.Cloud,
                            enabled = !state.data.isProvisioningBeadsRemote,
                            supportingText = beadsStatus?.error,
                            onClick = viewModel::startProvisionBeadsRemote,
                        )
                    }
                    ActionSheetItem(
                        text = stringResource(R.string.action_edit),
                        icon = LettaIcons.Edit,
                        onClick = viewModel::startProjectSettingsEdit,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_archive_action),
                        icon = LettaIcons.Archive,
                        onClick = viewModel::startArchiveProject,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_delete),
                        icon = LettaIcons.Delete,
                        onClick = viewModel::startDeleteProject,
                        destructive = true,
                    )
                }

                ConfirmDialog(
                    show = state.data.showProvisionBeadsRemoteDialog && selectedProject != null,
                    title = stringResource(R.string.screen_projects_beads_provision_action),
                    message = stringResource(R.string.screen_projects_beads_provision_confirm, selectedProject?.name.orEmpty()),
                    confirmText = stringResource(R.string.screen_projects_beads_provision_action),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = viewModel::confirmProvisionBeadsRemote,
                    onDismiss = viewModel::dismissProvisionBeadsRemote,
                )

                ConfirmDialog(
                    show = state.data.showArchiveProjectDialog && selectedProject != null,
                    title = stringResource(R.string.screen_projects_archive_action),
                    message = stringResource(
                        R.string.screen_projects_archive_confirm_message,
                        selectedProject?.name.orEmpty(),
                    ),
                    confirmText = stringResource(R.string.screen_projects_archive_action),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = viewModel::confirmArchiveProject,
                    onDismiss = viewModel::dismissArchiveProject,
                )

                ConfirmDialog(
                    show = state.data.showDeleteProjectDialog && selectedProject != null,
                    title = stringResource(R.string.action_delete),
                    message = stringResource(
                        R.string.screen_projects_delete_confirm_message,
                        selectedProject?.name.orEmpty(),
                    ),
                    confirmText = stringResource(R.string.action_delete),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = viewModel::confirmDeleteProject,
                    onDismiss = viewModel::dismissDeleteProject,
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun ProjectActionSheetHeader(
    project: ProjectSummary,
    beadsStatus: BeadsRemoteStatus?,
    pmAgent: PmAgentMetadata?,
    syncInFlight: Boolean,
    onPmAgentClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            beadsStatus?.let { status ->
                AssistChip(
                    onClick = {},
                    label = {
                        val suffix = status.provisionedAt?.let { " · ${formatRelativeTime(it)}" }.orEmpty()
                        Text(stringResource(R.string.screen_projects_beads_status_chip, status.status, suffix))
                    },
                )
            }
            pmAgent?.let { agent ->
                AssistChip(
                    onClick = { onPmAgentClick(agent.agentId) },
                    label = { Text(stringResource(R.string.screen_projects_pm_agent_chip, agent.name ?: agent.agentId)) },
                )
            }
            if (syncInFlight) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_projects_syncing_chip)) })
            }
        }
        val beadsError = beadsStatus?.error
        if (beadsStatus?.status == "failed" && beadsError != null) {
            Text(
                text = beadsError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        project.lastSyncAt?.let { lastSyncAt ->
            Text(
                text = stringResource(R.string.screen_projects_last_sync_supporting, formatRelativeTime(lastSyncAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectConversationSummaryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProjectTileMetaRow(
    statusLabel: String,
    statusContainerColor: Color,
    statusContentColor: Color,
    issueCount: Int,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
) {
    // letta-mobile-cygd: replaced the 8dp status dot + "• N issues"
    // string concatenation with semantic Surface badges. Status reads
    // as a colored chip; issue count is its own urgency-tinted chip
    // (tertiary for a handful, primary as it grows, error past ~20).
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusBadge(
            text = statusLabel,
            containerColor = statusContainerColor,
            contentColor = statusContentColor,
        )
        if (issueCount > 0) {
            val urgency = when {
                issueCount >= 20 -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    "$issueCount issues",
                )
                issueCount >= 6 -> Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "$issueCount issues",
                )
                else -> Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "$issueCount issues",
                )
            }
            StatusBadge(
                text = urgency.third,
                containerColor = urgency.first,
                contentColor = urgency.second,
            )
        }
        if (isPinned) {
            Icon(
                imageVector = LettaIcons.Pin,
                contentDescription = "Pinned project",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(
    project: ProjectSummary,
    isPinned: Boolean,
    onClick: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val lastActivity = project.updatedAt ?: project.lastSyncAt ?: project.lastCheckedAt ?: project.lastScanAt
    val issueCount = project.beadsIssueCount ?: project.issueCount ?: 0
    val statusLabel = project.status?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.common_unknown)

    // letta-mobile-cygd: status now drives both the Status badge color
    // and the tile-level border/wash so users get a glanceable read of
    // archived vs active. Active = primary border + container; archived
    // = muted outlineVariant border + softer surface; unknown defaults
    // to secondary so it doesn't masquerade as active.
    val (statusContainerColor, statusContentColor, tileBorderColor) = when (project.status?.lowercase()) {
        "active" -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        "archived" -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant,
        )
        else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.outlineVariant,
        )
    }

    val initials = remember(project.name, project.identifier) {
        project.name
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(separator = "") { it.take(1).uppercase() }
            .ifBlank { project.identifier.take(2).uppercase() }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onOpenActions()
                },
            ),
        shape = RoundedCornerShape(16.dp),
        color = LettaCardDefaults.listContainerColor,
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = tileBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                IconButton(
                    onClick = onOpenActions,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = LettaIcons.Menu,
                        contentDescription = stringResource(R.string.screen_projects_actions_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = project.identifier,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProjectTileMetaRow(
                    statusLabel = statusLabel,
                    statusContainerColor = statusContainerColor,
                    statusContentColor = statusContentColor,
                    issueCount = issueCount,
                    isPinned = isPinned,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                project.filesystemPath?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (lastActivity.isNullOrBlank()) {
                        stringResource(R.string.screen_projects_last_activity_unknown)
                    } else {
                        stringResource(
                            R.string.screen_projects_last_activity,
                            formatRelativeTime(lastActivity),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
