package com.letta.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ConnectionStatusBanner
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@Composable
internal fun ProjectAgentsCard(
    state: ProjectAgentsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
internal fun toneColor(tone: ProjectAgentStatusTone) = when (tone) {
    ProjectAgentStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    ProjectAgentStatusTone.Good -> MaterialTheme.colorScheme.tertiary
    ProjectAgentStatusTone.Busy -> MaterialTheme.colorScheme.primary
    ProjectAgentStatusTone.Error -> MaterialTheme.colorScheme.error
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectBugReportSummaryCard(
    state: ProjectBugReportUiState,
    onCreateReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
internal fun ProjectBugReportSheet(
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
                .testTag(AgentScaffoldTestTags.PROJECT_BUG_REPORT_SHEET)
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

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectBriefCard(
    brief: ProjectBriefUiState,
    onRetry: () -> Unit,
    onSaveSection: (ProjectBriefSectionKey, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editableState = remember { mutableStateMapOf<ProjectBriefSectionKey, String>() }
    val editingState = remember { mutableStateMapOf<ProjectBriefSectionKey, Boolean>() }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
internal fun sectionTitleFor(key: ProjectBriefSectionKey): String = when (key) {
    ProjectBriefSectionKey.Description -> stringResource(R.string.screen_project_brief_description_title)
    ProjectBriefSectionKey.KeyDecisions -> stringResource(R.string.screen_project_brief_decisions_title)
    ProjectBriefSectionKey.TechStack -> stringResource(R.string.screen_project_brief_tech_stack_title)
    ProjectBriefSectionKey.ActiveGoals -> stringResource(R.string.screen_project_brief_goals_title)
    ProjectBriefSectionKey.RecentChanges -> stringResource(R.string.screen_project_brief_recent_changes_title)
}

internal fun minLinesFor(key: ProjectBriefSectionKey): Int = when (key) {
    ProjectBriefSectionKey.Description -> 5
    ProjectBriefSectionKey.KeyDecisions -> 4
    ProjectBriefSectionKey.TechStack -> 3
    ProjectBriefSectionKey.ActiveGoals -> 4
    ProjectBriefSectionKey.RecentChanges -> 4
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectInfoTray(
    project: ProjectChatContext,
    agentsState: ProjectAgentsUiState,
    brief: ProjectBriefUiState,
    bugReports: ProjectBugReportUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRetryAgents: () -> Unit,
    onRetryBrief: () -> Unit,
    onSaveBriefSection: (ProjectBriefSectionKey, String) -> Unit,
    onCreateBugReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectContextCard(
                project = project,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_CONTEXT_CARD),
            )

            AnimatedVisibility(
                visible = expanded,
                enter = ChatMotion.expandEnter(),
                exit = ChatMotion.expandExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectAgentsCard(
                        state = agentsState,
                        onRetry = onRetryAgents,
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_AGENTS_CARD),
                    )
                    ProjectBriefCard(
                        brief = brief,
                        onRetry = onRetryBrief,
                        onSaveSection = onSaveBriefSection,
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BRIEF_CARD),
                    )
                    ProjectBugReportSummaryCard(
                        state = bugReports,
                        onCreateReport = onCreateBugReport,
                        modifier = Modifier.testTag(AgentScaffoldTestTags.PROJECT_BUG_SUMMARY_CARD),
                    )
                }
            }
        }
    }
}

@androidx.annotation.VisibleForTesting
@Composable
internal fun ProjectContextCard(
    project: ProjectChatContext,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
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

            FilledTonalButton(
                onClick = { onExpandedChange(!expanded) },
            ) {
                Icon(
                    imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Inline),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) stringResource(R.string.common_hide) else stringResource(R.string.common_details),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = ChatMotion.expandEnter(),
            exit = ChatMotion.expandExit(),
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

@Composable
internal fun ProjectInfoLine(
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
