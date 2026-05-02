package com.letta.mobile.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.letta.mobile.R
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ConnectionState
import com.letta.mobile.ui.components.ConnectionStatusBanner
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.FormItem

import com.letta.mobile.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.listItemHeadline
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScaffold(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onSwitchConversation: ((String, String?) -> Unit)? = null,
    viewModel: AdminChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConversationPicker by remember { mutableStateOf(false) }
    var showBugReportSheet by remember { mutableStateOf(false) }
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()

    val agentName = uiState.agentName
    val agentId = viewModel.agentId
    val conversationId = viewModel.conversationId
    val projectContext = viewModel.projectContext
    val screenTitle = projectContext?.name ?: agentName.ifBlank { stringResource(R.string.screen_chat_title) }
    // Compact top bar — was LargeFlexibleTopAppBar (~152dp expanded), now a
    // standard TopAppBar at ~64dp. The chat surface is content-dense and
    // doesn't benefit from a big collapsing hero header; the agent name fits
    // fine in a single compact title row.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    agentName = agentName,
                    agentId = agentId,
                    messageCount = uiState.messages.size,
                    contextWindow = uiState.contextWindow,
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings(agentId)
                    },
                    onArchivalMemory = {
                        scope.launch { drawerState.close() }
                        onNavigateToArchival?.invoke(agentId)
                    },
                    onTools = {
                        scope.launch { drawerState.close() }
                        onNavigateToTools?.invoke()
                    },
                    onResetMessages = {
                        scope.launch { drawerState.close() }
                        viewModel.resetMessages()
                    },
                    onRefreshContextWindow = viewModel::refreshContextWindow,
                    onClose = { scope.launch { drawerState.close() } },
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showConversationPicker = true }
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = screenTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                LettaIcons.ArrowDropDown,
                                contentDescription = "Switch conversation",
                                modifier = Modifier.size(LettaIconSizing.Inline),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = LettaTopBarDefaults.topAppBarColors(),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            viewModel.refreshContextWindow()
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(LettaIcons.Menu, "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                if (projectContext != null) {
                    FloatingActionButton(onClick = { showBugReportSheet = true }) {
                        Icon(LettaIcons.Error, contentDescription = stringResource(R.string.screen_project_bug_report_open))
                    }
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                projectContext?.let { project ->
                    ProjectContextCard(project = project)
                    ProjectAgentsCard(
                        state = uiState.projectAgents,
                        onRetry = viewModel::loadProjectAgents,
                    )
                    ProjectBriefCard(
                        brief = uiState.projectBrief,
                        onRetry = viewModel::loadProjectBrief,
                        onSaveSection = viewModel::saveProjectBriefSection,
                    )
                    ProjectBugReportSummaryCard(
                        state = uiState.bugReports,
                        onCreateReport = { showBugReportSheet = true },
                    )
                }
                ChatScreen(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    chatBackground = chatBackground,
                    onBugCommand = { showBugReportSheet = true },
                )
            }
        }
    }

    if (showConversationPicker) {
        ConversationPickerSheet(
            agentId = agentId,
            currentConversationId = conversationId,
            onDismiss = { showConversationPicker = false },
            onConversationSelected = { action ->
                showConversationPicker = false
                onSwitchConversation?.invoke(agentId, action.conversationId)
            },
            onNewConversation = { action ->
                showConversationPicker = false
                onSwitchConversation?.invoke(agentId, action.conversationId)
            },
        )
    }

    if (showBugReportSheet && projectContext != null) {
        ProjectBugReportSheet(
            state = uiState.bugReports,
            onDismiss = { showBugReportSheet = false },
            onSubmit = {
                viewModel.submitStructuredBugReport(it)
                showBugReportSheet = false
            },
        )
    }
}

@Composable
private fun ProjectAgentsCard(
    state: ProjectAgentsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormItem(
                label = {
                    Text(
                        text = stringResource(R.string.screen_project_agents_title),
                        style = MaterialTheme.typography.listItemHeadline,
                    )
                },
                description = {
                    Text(stringResource(R.string.screen_project_agents_subtitle))
                },
                tail = {
                    OutlinedButton(onClick = onRetry, enabled = !state.isLoading) {
                        Text(stringResource(R.string.action_refresh))
                    }
                },
            )

            if (state.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.screen_project_agents_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.error?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            if (!state.isLoading && state.agents.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_project_agents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.agents.forEach { agent ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.titleSmall)
                                agent.model?.let {
                                    Text(
                                        text = stringResource(R.string.screen_project_agents_model, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(agent.statusLabel) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier.size(10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Card(
                                            modifier = Modifier.size(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = toneColor(agent.statusTone)),
                                        ) {}
                                    }
                                },
                            )
                        }

                        agent.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        agent.lastActivity?.let {
                            Text(
                                text = stringResource(
                                    R.string.screen_project_agents_last_activity,
                                    formatRelativeTime(it),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: ProjectAgentStatusTone) = when (tone) {
    ProjectAgentStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    ProjectAgentStatusTone.Good -> MaterialTheme.colorScheme.tertiary
    ProjectAgentStatusTone.Busy -> MaterialTheme.colorScheme.primary
    ProjectAgentStatusTone.Error -> MaterialTheme.colorScheme.error
}

@Composable
private fun ProjectBugReportSummaryCard(
    state: ProjectBugReportUiState,
    onCreateReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormItem(
                label = { Text(stringResource(R.string.screen_project_bug_report_title), style = MaterialTheme.typography.listItemHeadline) },
                description = {
                    Text(stringResource(R.string.screen_project_bug_report_subtitle))
                },
                tail = {
                    OutlinedButton(onClick = onCreateReport) {
                        Text(stringResource(R.string.screen_project_bug_report_open))
                    }
                },
            )

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.recentReports.take(3).forEach { report ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(report.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(
                                R.string.screen_project_bug_report_recent_meta,
                                report.severity,
                                formatRelativeTime(report.createdAt),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectBugReportSheet(
    state: ProjectBugReportUiState,
    onDismiss: () -> Unit,
    onSubmit: (ProjectBugReportDraft) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(BugSeverity.Medium) }
    var severityExpanded by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(setOf("ui", "backend", "sync")) }
    var attachments by remember { mutableStateOf(listOf<String>()) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_project_bug_report_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.screen_project_bug_report_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.screen_project_bug_report_field_title)) },
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.screen_project_bug_report_field_description)) },
                minLines = 5,
            )

            ExposedDropdownMenuBox(
                expanded = severityExpanded,
                onExpandedChange = { severityExpanded = it },
            ) {
                OutlinedTextField(
                    value = severity.wireValue.replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryEditable),
                    readOnly = true,
                    label = { Text(stringResource(R.string.screen_project_bug_report_field_severity)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = severityExpanded) },
                )
                DropdownMenu(
                    expanded = severityExpanded,
                    onDismissRequest = { severityExpanded = false },
                ) {
                    BugSeverity.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.wireValue.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                severity = option
                                severityExpanded = false
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.screen_project_bug_report_field_tags),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ui", "backend", "sync", "crash").forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = {
                                selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }

            FormItem(
                label = { Text(stringResource(R.string.screen_project_bug_report_field_attachments)) },
                description = {
                    Text(
                        if (attachments.isEmpty()) {
                            stringResource(R.string.screen_project_bug_report_attachments_empty)
                        } else {
                            attachments.joinToString("\n")
                        }
                    )
                },
                tail = {
                    OutlinedButton(onClick = { showAttachmentSheet = true }) {
                        Text(stringResource(R.string.screen_project_bug_report_add_attachment))
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        onSubmit(
                            ProjectBugReportDraft(
                                title = title,
                                description = description,
                                severity = severity,
                                tags = selectedTags.toList().sorted().toImmutableList(),
                                attachmentReferences = attachments.toImmutableList(),
                            )
                        )
                    },
                    enabled = title.isNotBlank() && description.isNotBlank() && !state.isSubmitting,
                ) {
                    Text(stringResource(R.string.screen_project_bug_report_submit))
                }
            }
        }
    }

    ActionSheet(
        show = showAttachmentSheet,
        onDismiss = { showAttachmentSheet = false },
        title = stringResource(R.string.screen_project_bug_report_attachment_title),
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_camera),
            icon = LettaIcons.Error,
            onClick = {
                attachments = attachments + "camera://capture-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_gallery),
            icon = LettaIcons.FileOpen,
            onClick = {
                attachments = attachments + "gallery://selection-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.screen_project_bug_report_attachment_recording),
            icon = LettaIcons.Play,
            onClick = {
                attachments = attachments + "recording://screen-${System.currentTimeMillis()}"
                showAttachmentSheet = false
            },
        )
    }
}

@Composable
private fun ProjectBriefCard(
    brief: ProjectBriefUiState,
    onRetry: () -> Unit,
    onSaveSection: (ProjectBriefSectionKey, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editableState = remember { mutableStateMapOf<ProjectBriefSectionKey, String>() }
    val editingState = remember { mutableStateMapOf<ProjectBriefSectionKey, Boolean>() }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Accordions(
            title = stringResource(R.string.screen_project_brief_title),
            subtitle = stringResource(R.string.screen_project_brief_subtitle),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (brief.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.screen_project_brief_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                brief.error?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            OutlinedButton(onClick = onRetry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }

                if (!brief.isLoading && brief.sections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_project_brief_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProjectBriefSectionKey.entries.forEach { key ->
                    val section = brief.sections[key] ?: return@forEach
                    val isEditing = editingState[key] == true
                    val draft = editableState[key] ?: section.content

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FormItem(
                                label = {
                                    Text(
                                        text = sectionTitleFor(key),
                                        style = MaterialTheme.typography.listItemHeadline,
                                    )
                                },
                                description = {
                                    section.updatedAt?.let {
                                        Text(
                                            text = stringResource(
                                                R.string.screen_project_brief_last_updated,
                                                formatRelativeTime(it),
                                            ),
                                        )
                                    } ?: Text(stringResource(R.string.screen_project_brief_memory_backed))
                                },
                                tail = {
                                    OutlinedButton(
                                        onClick = {
                                            if (isEditing) {
                                                onSaveSection(key, draft)
                                                editingState[key] = false
                                            } else {
                                                editableState[key] = section.content
                                                editingState[key] = true
                                            }
                                        },
                                        enabled = !brief.isSaving,
                                    ) {
                                        Text(
                                            stringResource(
                                                if (isEditing) R.string.action_save else R.string.action_edit
                                            )
                                        )
                                    }
                                },
                            )

                            if (isEditing) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { editableState[key] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = minLinesFor(key),
                                    enabled = !brief.isSaving,
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    MarkdownText(
                                        text = section.content,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionTitleFor(key: ProjectBriefSectionKey): String = when (key) {
    ProjectBriefSectionKey.Description -> stringResource(R.string.screen_project_brief_description_title)
    ProjectBriefSectionKey.KeyDecisions -> stringResource(R.string.screen_project_brief_decisions_title)
    ProjectBriefSectionKey.TechStack -> stringResource(R.string.screen_project_brief_tech_stack_title)
    ProjectBriefSectionKey.ActiveGoals -> stringResource(R.string.screen_project_brief_goals_title)
    ProjectBriefSectionKey.RecentChanges -> stringResource(R.string.screen_project_brief_recent_changes_title)
}

private fun minLinesFor(key: ProjectBriefSectionKey): Int = when (key) {
    ProjectBriefSectionKey.Description -> 5
    ProjectBriefSectionKey.KeyDecisions -> 4
    ProjectBriefSectionKey.TechStack -> 3
    ProjectBriefSectionKey.ActiveGoals -> 4
    ProjectBriefSectionKey.RecentChanges -> 4
}

@Composable
private fun ProjectContextCard(
    project: ProjectChatContext,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(project.identifier) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.identifier,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AssistChip(
                    onClick = { expanded = !expanded },
                    label = {
                        Text(
                            text = if (expanded) stringResource(R.string.common_hide) else stringResource(R.string.common_details),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(LettaIconSizing.Inline),
                        )
                    },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_path_label),
                        value = project.filesystemPath,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_git_url_label),
                        value = project.gitUrl,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_active_agents_label),
                        value = project.activeCodingAgents,
                    )
                    ProjectInfoLine(
                        label = stringResource(R.string.screen_project_chat_last_sync_label),
                        value = project.lastSyncAt?.let(::formatRelativeTime),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectInfoLine(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
        )
        Text(
            text = value ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationPickerSheet(
    agentId: String,
    currentConversationId: String?,
    onDismiss: () -> Unit,
    onConversationSelected: (ConversationSwitchAction) -> Unit,
    onNewConversation: (ConversationSwitchAction) -> Unit,
) {
    val viewModel = hiltViewModel<ConversationPickerViewModel>()
    val conversationRepo = viewModel.conversationRepository
    val conversations by conversationRepo.getConversations(agentId).collectAsStateWithLifecycle(emptyList())
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode = selectedIds.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDismissingForAction by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val selectionColors = MaterialTheme.customColors

    fun dismissThen(action: () -> Unit) {
        if (isDismissingForAction) return
        isDismissingForAction = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                action()
                viewModel.clearSelection()
                onDismiss()
            } else {
                isDismissingForAction = false
            }
        }
    }

    LaunchedEffect(agentId) {
        conversationRepo.refreshConversations(agentId)
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            viewModel.clearSelection()
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            LettaIcons.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.common_conversations),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    dismissThen {
                        onNewConversation(ConversationSwitchAction.NewConversation)
                    }
                },
                enabled = !isDismissingForAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screen_conversations_new_action))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (conversations.isEmpty()) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val isActive = conversation.id == currentConversationId
                        val isChecked = conversation.id in selectedIds
                        val containerColor = when {
                            isChecked -> selectionColors.selectionContainer
                            isActive -> MaterialTheme.colorScheme.primaryContainer
                            else -> CardDefaults.cardColors().containerColor
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(conversation.id)
                                        } else {
                                            dismissThen {
                                                onConversationSelected(
                                                    ConversationSwitchAction.ExistingConversation(conversation.id)
                                                )
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(conversation.id)
                                    },
                                ),
                            colors = CardDefaults.cardColors(containerColor = containerColor),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = conversation.summary ?: "Conversation",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val timeText = formatRelativeTime(conversation.lastMessageAt ?: conversation.createdAt)
                                    if (timeText.isNotBlank()) {
                                        Text(
                                            text = timeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (isChecked) {
                                    Icon(
                                        LettaIcons.CheckCircle,
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                                        tint = selectionColors.selectionIndicator,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = "Delete ${selectedIds.size} conversation${if (selectedIds.size > 1) "s" else ""}? This cannot be undone.",
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            showDeleteConfirm = false
            viewModel.deleteSelected(
                agentId = agentId,
                activeConversationId = currentConversationId,
                onActiveDeleted = {
                    dismissThen {
                        onNewConversation(ConversationSwitchAction.NewConversation)
                    }
                },
            )
        },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )
}

sealed interface ConversationSwitchAction {
    val conversationId: String?

    data object NewConversation : ConversationSwitchAction {
        override val conversationId: String? = null
    }

    data class ExistingConversation(override val conversationId: String) : ConversationSwitchAction
}

@HiltViewModel
class ConversationPickerViewModel @Inject constructor(
    val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun toggleSelection(id: String) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected(agentId: String, onActiveDeleted: () -> Unit = {}, activeConversationId: String? = null) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        val deletedActive = activeConversationId != null && activeConversationId in ids
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            for (id in ids) {
                try {
                    conversationRepository.deleteConversation(id, agentId)
                } catch (_: Exception) { /* individual failures are handled by the repository's rollback */ }
            }
            if (deletedActive) onActiveDeleted()
        }
    }
}

@Composable
private fun ContextWindowCard(
    state: ContextWindowUiState,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LettaIcons.Database,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.screen_chat_context_window_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            LettaIcons.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.maxTokens > 0) {
                val progress = (state.currentTokens.toFloat() / state.maxTokens.toFloat()).coerceIn(0f, 1f)
                Text(
                    text = stringResource(
                        R.string.screen_chat_context_window_usage,
                        formatDrawerNumber(state.currentTokens),
                        formatDrawerNumber(state.maxTokens),
                        state.usagePercent,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_messages),
                    value = stringResource(
                        R.string.screen_chat_context_window_messages_value,
                        formatDrawerNumber(state.messageTokens),
                        state.messageCount,
                    ),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_memory),
                    value = formatDrawerNumber(
                        state.coreMemoryTokens + state.externalMemoryTokens + state.summaryMemoryTokens,
                    ),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_tools),
                    value = formatDrawerNumber(state.toolTokens),
                )
                ContextMetricRow(
                    label = stringResource(R.string.screen_chat_context_window_system),
                    value = formatDrawerNumber(state.systemTokens),
                )
                Text(
                    text = stringResource(
                        R.string.screen_chat_context_window_memory_counts,
                        state.recallMemoryCount,
                        state.archivalMemoryCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.screen_chat_context_window_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContextMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDrawerNumber(value: Int): String = String.format(Locale.US, "%,d", value)

@Composable
private fun DrawerContent(
    agentName: String,
    agentId: String,
    messageCount: Int,
    contextWindow: ContextWindowUiState,
    onEditAgent: () -> Unit,
    onArchivalMemory: () -> Unit,
    onTools: () -> Unit = {},
    onResetMessages: () -> Unit = {},
    onRefreshContextWindow: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                LettaIcons.Agent,
                contentDescription = "Agent",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = agentName.ifBlank { "Agent" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$messageCount messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        ContextWindowCard(
            state = contextWindow,
            onRefresh = onRefreshContextWindow,
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Edit, contentDescription = "Edit") },
            label = { Text(stringResource(R.string.screen_drawer_edit_agent)) },
            selected = false,
            onClick = onEditAgent,
        )

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Inventory, contentDescription = "Archival") },
            label = { Text(stringResource(R.string.screen_drawer_archival)) },
            selected = false,
            onClick = onArchivalMemory,
        )

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Tool, contentDescription = "Tools") },
            label = { Text(stringResource(R.string.common_tools)) },
            selected = false,
            onClick = onTools,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Delete, contentDescription = "Reset") },
            label = { Text("Reset Messages") },
            selected = false,
            onClick = onResetMessages,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = agentId.take(12) + "\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
