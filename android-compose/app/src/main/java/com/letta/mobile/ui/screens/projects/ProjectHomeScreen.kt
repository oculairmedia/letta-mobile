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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
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
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import com.letta.mobile.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(
    onNavigateBack: (() -> Unit)?,
    onNavigateToProjectChat: (project: ProjectSummary) -> Unit,
    onNavigateToSettings: () -> Unit,
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
    val conversationalGoalLabel = stringResource(R.string.screen_projects_new_project_goal_label)
    val conversationalGoalHelper = stringResource(R.string.screen_projects_new_project_goal_helper)
    val conversationalNameLabel = stringResource(R.string.screen_projects_new_project_name_label)
    val conversationalNameHelper = stringResource(R.string.screen_projects_new_project_name_helper)
    val conversationalPathLabel = stringResource(R.string.screen_projects_new_project_path_label)
    val conversationalPathHelper = stringResource(R.string.screen_projects_new_project_path_helper)
    val conversationalPathMissing = stringResource(R.string.screen_projects_settings_path_missing)
    val conversationalPathAbsolute = stringResource(R.string.screen_projects_settings_path_must_be_absolute)
    val conversationalGitUrlLabel = stringResource(R.string.screen_projects_new_project_git_url_label)
    val conversationalGitUrlHelper = stringResource(R.string.screen_projects_new_project_git_url_helper)

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
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(LettaIcons.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.showCreateProjectOptions()
                }
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
                                    onClick = {
                                        val agentId = project.lettaAgentId
                                        if (agentId.isNullOrBlank()) {
                                            snackbar.dispatch(missingAgentMessage.format(project.name))
                                        } else {
                                            onNavigateToProjectChat(project)
                                        }
                                    },
                                    onOpenActions = { viewModel.selectProject(project.identifier) },
                                )
                            }
                        }
                    }
                }

                val selectedProject = viewModel.currentProject()
                ActionSheet(
                    show = state.data.showCreateOptions,
                    onDismiss = viewModel::dismissCreateProjectOptions,
                    title = stringResource(R.string.screen_projects_new_project),
                ) {
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_new_project_manual_action),
                        icon = LettaIcons.Edit,
                        onClick = viewModel::startManualProjectCreation,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_new_project_conversational_action),
                        icon = LettaIcons.Chat,
                        onClick = viewModel::startConversationalProjectCreation,
                    )
                }

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

                MultiFieldInputDialog(
                    show = state.data.showConversationalCreateDialog,
                    title = stringResource(
                        when (state.data.conversationalProjectStep) {
                            ConversationalProjectStep.Goal -> R.string.screen_projects_new_project_conversational_goal_title
                            ConversationalProjectStep.Name -> R.string.screen_projects_new_project_conversational_name_title
                            ConversationalProjectStep.FilesystemPath -> R.string.screen_projects_new_project_conversational_path_title
                            ConversationalProjectStep.GitUrl -> R.string.screen_projects_new_project_conversational_git_url_title
                            ConversationalProjectStep.Review -> R.string.screen_projects_new_project_conversational_review_title
                        }
                    ),
                    confirmText = stringResource(
                        when (state.data.conversationalProjectStep) {
                            ConversationalProjectStep.Review -> R.string.action_create
                            else -> R.string.screen_projects_new_project_conversational_continue
                        }
                    ),
                    dismissText = stringResource(
                        when (state.data.conversationalProjectStep) {
                            ConversationalProjectStep.Goal -> R.string.action_cancel
                            else -> R.string.action_back
                        }
                    ),
                    onDismiss = viewModel::dismissConversationalProjectCreation,
                    confirmEnabled = state.data.conversationalProjectDraft.isReadyFor(state.data.conversationalProjectStep) && !state.data.isSubmittingConversationalCreate,
                    onConfirm = viewModel::submitConversationalProjectCreation,
                ) {
                    val draft = state.data.conversationalProjectDraft
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(
                                when (state.data.conversationalProjectStep) {
                                    ConversationalProjectStep.Goal -> R.string.screen_projects_new_project_conversational_goal_prompt
                                    ConversationalProjectStep.Name -> R.string.screen_projects_new_project_conversational_name_prompt
                                    ConversationalProjectStep.FilesystemPath -> R.string.screen_projects_new_project_conversational_path_prompt
                                    ConversationalProjectStep.GitUrl -> R.string.screen_projects_new_project_conversational_git_url_prompt
                                    ConversationalProjectStep.Review -> R.string.screen_projects_new_project_conversational_review_prompt
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        when (state.data.conversationalProjectStep) {
                            ConversationalProjectStep.Goal -> {
                                FormItem(
                                    label = { Text(conversationalGoalLabel) },
                                    description = { Text(conversationalGoalHelper) },
                                ) {
                                    OutlinedTextField(
                                        value = draft.goal,
                                        onValueChange = {
                                            viewModel.updateConversationalProjectDraft(draft.copy(goal = it))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 4,
                                    )
                                }
                            }

                            ConversationalProjectStep.Name -> {
                                if (draft.goal.isNotBlank()) {
                                    ProjectConversationSummaryChip(
                                        label = stringResource(R.string.common_description),
                                        value = draft.goal,
                                    )
                                }
                                FormItem(
                                    label = { Text(conversationalNameLabel) },
                                    description = { Text(conversationalNameHelper) },
                                ) {
                                    OutlinedTextField(
                                        value = draft.name,
                                        onValueChange = {
                                            viewModel.updateConversationalProjectDraft(draft.copy(name = it))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                }
                            }

                            ConversationalProjectStep.FilesystemPath -> {
                                val pathValidation = draft.filesystemPathValidation()
                                if (draft.goal.isNotBlank()) {
                                    ProjectConversationSummaryChip(
                                        label = stringResource(R.string.common_description),
                                        value = draft.goal,
                                    )
                                }
                                if (draft.name.isNotBlank()) {
                                    ProjectConversationSummaryChip(
                                        label = conversationalNameLabel,
                                        value = draft.name,
                                    )
                                }
                                FormItem(
                                    label = { Text(conversationalPathLabel) },
                                    description = {
                                        Text(
                                            when (pathValidation) {
                                                ConversationalProjectDraft.FilesystemPathValidation.Missing -> conversationalPathMissing
                                                ConversationalProjectDraft.FilesystemPathValidation.MustBeAbsolute -> conversationalPathAbsolute
                                                ConversationalProjectDraft.FilesystemPathValidation.Valid -> conversationalPathHelper
                                            }
                                        )
                                    },
                                ) {
                                    OutlinedTextField(
                                        value = draft.filesystemPath,
                                        onValueChange = {
                                            viewModel.updateConversationalProjectDraft(draft.copy(filesystemPath = it))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        isError = pathValidation != ConversationalProjectDraft.FilesystemPathValidation.Valid,
                                        singleLine = true,
                                    )
                                }
                            }

                            ConversationalProjectStep.GitUrl -> {
                                ProjectConversationSummaryChip(
                                    label = conversationalNameLabel,
                                    value = draft.name,
                                )
                                ProjectConversationSummaryChip(
                                    label = conversationalPathLabel,
                                    value = draft.filesystemPath,
                                )
                                FormItem(
                                    label = { Text(conversationalGitUrlLabel) },
                                    description = { Text(conversationalGitUrlHelper) },
                                ) {
                                    OutlinedTextField(
                                        value = draft.gitUrl,
                                        onValueChange = {
                                            viewModel.updateConversationalProjectDraft(draft.copy(gitUrl = it))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                }
                            }

                            ConversationalProjectStep.Review -> {
                                if (draft.goal.isNotBlank()) {
                                    ProjectConversationSummaryChip(
                                        label = conversationalGoalLabel,
                                        value = draft.goal,
                                    )
                                }
                                ProjectConversationSummaryChip(
                                    label = conversationalNameLabel,
                                    value = draft.name,
                                )
                                ProjectConversationSummaryChip(
                                    label = conversationalPathLabel,
                                    value = draft.filesystemPath,
                                )
                                ProjectConversationSummaryChip(
                                    label = conversationalGitUrlLabel,
                                    value = draft.gitUrl.ifBlank {
                                        stringResource(R.string.screen_projects_new_project_git_url_none)
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.screen_projects_new_project_conversational_review_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

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
                    ActionSheetItem(
                        text = stringResource(R.string.screen_projects_open_chat_action),
                        icon = LettaIcons.Chat,
                        onClick = {
                            viewModel.selectProject(null)
                            val agentId = project.lettaAgentId
                            if (agentId.isNullOrBlank()) {
                                snackbar.dispatch(missingAgentMessage.format(project.name))
                            } else {
                                onNavigateToProjectChat(project)
                            }
                        },
                    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(
    project: ProjectSummary,
    onClick: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val lastActivity = project.updatedAt ?: project.lastSyncAt ?: project.lastCheckedAt ?: project.lastScanAt
    val statusColor = when (project.status?.lowercase()) {
        "active" -> MaterialTheme.colorScheme.tertiary
        "archived" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val initials = project.name
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString(separator = "") { it.take(1).uppercase() }
        .ifBlank { project.identifier.take(2).uppercase() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(182.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onOpenActions()
                },
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
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

                BadgedBox(
                    badge = {
                        val issueCount = project.beadsIssueCount ?: project.issueCount ?: 0
                        if (issueCount > 0) {
                            Badge {
                                Text(issueCount.toString())
                            }
                        }
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(50),
                                    color = statusColor,
                                ) {}
                            }
                            Text(
                                text = project.status?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.common_unknown),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
