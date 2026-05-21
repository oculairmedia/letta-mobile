package com.letta.mobile.ui.screens.runs

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.StatusChip
import com.letta.mobile.ui.components.TagDrillInDialog
import com.letta.mobile.ui.components.TelemetryGrid
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.tags.TagDrillInEntityType
import com.letta.mobile.ui.tags.TagDrillInSource
import com.letta.mobile.ui.tags.TagDrillInViewModel
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.motion.StaggeredListItem
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.letta.mobile.ui.icons.LettaIcons
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunMonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: RunMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tagDrillInViewModel: TagDrillInViewModel = hiltViewModel()
    val tagDrillInState by tagDrillInViewModel.uiState.collectAsStateWithLifecycle()
    var cancelTarget by remember { mutableStateOf<Run?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Run?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_runs_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_runs_title)) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
            ExpandableSearchField(
                query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                onQueryChange = viewModel::updateSearchQuery,
                onClear = { viewModel.updateSearchQuery("") },
                expanded = isSearchExpanded,
                placeholder = stringResource(R.string.screen_runs_search_hint),
            )
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadRuns() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredRuns = remember(state.data.runs, state.data.searchQuery) {
                    viewModel.getFilteredRuns()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.screen_runs_active_only), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.data.activeOnly,
                            onCheckedChange = viewModel::toggleActiveOnly,
                        )
                    }

                    if (filteredRuns.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.AccessTime,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_runs_empty)
                            } else {
                                stringResource(R.string.screen_runs_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(filteredRuns, key = { _, run -> run.id }) { index, run ->
                                StaggeredListItem(index = index) {
                                    RunCard(
                                        run = run,
                                        onInspect = { viewModel.inspectRun(run.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedRun = (uiState as? UiState.Success)?.data?.selectedRun
    selectedRun?.let { run ->
        val state = (uiState as? UiState.Success)?.data
        RunDetailDialog(
            run = run,
            messages = state?.selectedRunMessages.orEmpty(),
            usage = state?.selectedRunUsage,
            metrics = state?.selectedRunMetrics,
            steps = state?.selectedRunSteps.orEmpty(),
            onInspectStep = viewModel::inspectStep,
            onDismiss = { viewModel.clearSelectedRun() },
            onTagClick = { stepId, tag ->
                tagDrillInViewModel.showTag(tag, TagDrillInSource(TagDrillInEntityType.STEP, stepId))
            },
            onCancel = if (run.isTerminalStatus()) null else {
                {
                    viewModel.clearSelectedRun()
                    cancelTarget = run
                }
            },
            onDelete = if (run.isTerminalStatus()) {
                {
                    viewModel.clearSelectedRun()
                    deleteTarget = run
                }
            } else null,
        )
    }

    val selectedStep = (uiState as? UiState.Success)?.data?.selectedStep
    selectedStep?.let { step ->
        val state = (uiState as? UiState.Success)?.data
        StepDetailDialog(
            step = step,
            messages = state?.selectedStepMessages.orEmpty(),
            metrics = state?.selectedStepMetrics,
            trace = state?.selectedStepTrace,
            onDismiss = { viewModel.clearSelectedStep() },
            onTagClick = { tag ->
                tagDrillInViewModel.showTag(tag, TagDrillInSource(TagDrillInEntityType.STEP, step.id))
            },
            onSetPositiveFeedback = { viewModel.updateStepFeedback(step.id, "positive") },
            onSetNegativeFeedback = { viewModel.updateStepFeedback(step.id, "negative") },
            onClearFeedback = { viewModel.updateStepFeedback(step.id, null) },
        )
    }

    TagDrillInDialog(
        state = tagDrillInState,
        onDismiss = tagDrillInViewModel::dismiss,
    )

    cancelTarget?.let { run ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_runs_cancel_title),
            message = stringResource(R.string.screen_runs_cancel_confirm, run.id),
            confirmText = stringResource(R.string.action_cancel_run),
            dismissText = stringResource(R.string.action_close),
            onConfirm = {
                viewModel.cancelRun(run.id)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
            destructive = true,
        )
    }

    deleteTarget?.let { run ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_runs_delete_title),
            message = stringResource(R.string.screen_runs_delete_confirm, run.id),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_close),
            onConfirm = {
                viewModel.deleteRun(run.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    val operationError = (uiState as? UiState.Success)?.data?.operationError
    if (operationError != null) {
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_error),
            message = operationError,
            confirmText = stringResource(R.string.action_dismiss),
            dismissText = stringResource(R.string.action_dismiss),
            onConfirm = { viewModel.clearOperationError() },
            onDismiss = { viewModel.clearOperationError() },
        )
    }
}

@Composable
private fun RunCard(
    run: Run,
    onInspect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val active = isActiveRunStatus(run.status)
    val containerColor = runCardContainerColor(run.status)

    val startEpochMs = remember(run.createdAt) { parseInstantMillis(run.createdAt) }
    val frozenDuration = remember(run.totalDurationNs, run.completedAt, startEpochMs) {
        val totalNs = run.totalDurationNs
        val completedAt = run.completedAt
        when {
            totalNs != null -> formatElapsedDuration(totalNs / 1_000_000L)
            startEpochMs != null && completedAt != null -> {
                val end = parseInstantMillis(completedAt)
                if (end != null) formatElapsedDuration(end - startEpochMs) else "--:--"
            }
            else -> "--:--"
        }
    }
    var liveDuration by remember(run.id, run.status) { mutableStateOf(frozenDuration) }
    if (active && startEpochMs != null) {
        LaunchedEffect(run.id, startEpochMs) {
            while (true) {
                liveDuration = formatElapsedDuration(System.currentTimeMillis() - startEpochMs)
                delay(1000L)
            }
        }
    }
    val durationText = if (active && startEpochMs != null) liveDuration else frozenDuration

    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Row 1 — primary: status chip + live/frozen duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    run.status?.let { status -> StatusChip(status = status) }
                    if (run.background == true) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.screen_runs_background_chip)) },
                        )
                    }
                }
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = LettaCodeFont,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            // Row 2 — supporting: agent id, optional conversation id
            Text(
                text = stringResource(R.string.screen_runs_agent_label, run.agentId),
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            run.conversationId?.let { conversationId ->
                Text(
                    text = stringResource(R.string.screen_runs_conversation_label, conversationId),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Row 3 — metadata: timestamp + low-contrast truncated UUID pill (click-to-copy)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = run.createdAt
                        ?.let { stringResource(R.string.screen_runs_created_label, formatRelativeTime(it)) }
                        .orEmpty(),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = truncateRunId(run.id),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = LettaCodeFont,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(run.id))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun runCardContainerColor(status: String?): androidx.compose.ui.graphics.Color {
    return when (status?.trim()?.lowercase(Locale.ROOT)) {
        "error", "failed", "cancelled", "expired" -> MaterialTheme.colorScheme.errorContainer
        "completed" -> MaterialTheme.colorScheme.secondaryContainer
        "running", "active", "created", "pending", "processing", "working", "busy" ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
}

private fun isActiveRunStatus(status: String?): Boolean {
    val normalized = status?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized in activeRunStatuses
}

private val activeRunStatuses = setOf(
    "running", "active", "created", "pending", "processing", "working", "busy",
)

private fun parseInstantMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

private fun formatElapsedDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun truncateRunId(id: String): String =
    if (id.length <= 18) id else id.take(8) + "…" + id.takeLast(8)

@Composable
private fun RunDetailDialog(
    run: Run,
    messages: List<LettaMessage>,
    usage: UsageStatistics?,
    metrics: RunMetrics?,
    steps: List<Step>,
    onInspectStep: (String) -> Unit,
    onDismiss: () -> Unit,
    onTagClick: (String, String) -> Unit,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    ConfirmDialog(
        show = true,
        title = run.id,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup {
                    run.status?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_status_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    run.stopReason?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_stop_reason_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_agent_label, "")) },
                        supportingContent = { Text(run.agentId, style = MaterialTheme.typography.listItemSupporting) },
                    )
                    run.conversationId?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_conversation_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    run.createdAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_created_exact_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    run.completedAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_completed_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    run.callbackUrl?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_callback_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    run.callbackSentAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_callback_sent_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    run.callbackStatusCode?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_callback_status_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    run.callbackError?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_callback_error_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    run.totalDurationNs?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_total_duration_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    run.ttftNs?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_ttft_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                }
            }
            if (run.metadata.isNotEmpty()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.screen_runs_metadata_title)) }) {
                        run.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                            item(
                                headlineContent = { Text(key, style = MaterialTheme.typography.listItemSupporting) },
                                supportingContent = {
                                    Text(
                                        text = value.toDisplayString(),
                                        style = MaterialTheme.typography.listItemSupporting,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            run.requestConfig?.let { config ->
                item {
                    CardGroup(title = { Text(stringResource(R.string.screen_runs_request_config_title)) }) {
                        config.assistantMessageToolName?.let {
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_request_config_assistant_tool_name_label, "")) },
                                supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        config.assistantMessageToolKwarg?.let {
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_request_config_assistant_tool_kwarg_label, "")) },
                                supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        config.includeReturnMessageTypes?.takeIf { it.isNotEmpty() }?.let {
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_request_config_include_return_types_label, "")) },
                                supportingContent = { Text(it.joinToString(", "), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        config.useAssistantMessage?.let {
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_request_config_use_assistant_message_label, "")) },
                                supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                    }
                }
            }
            usage?.let {
                item {
                    CardGroup(title = { Text(stringResource(R.string.screen_runs_usage_title)) }) {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_usage_prompt_tokens_label, "")) },
                            supportingContent = { Text((it.promptTokens ?: 0).toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_usage_completion_tokens_label, "")) },
                            supportingContent = { Text((it.completionTokens ?: 0).toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_usage_total_tokens_label, "")) },
                            supportingContent = { Text((it.totalTokens ?: 0).toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                }
            }
            metrics?.let {
                item {
                    CardGroup(title = { Text(stringResource(R.string.screen_runs_metrics_title)) }) {
                        it.organizationId?.let { organizationId ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_organization_label, "")) },
                                supportingContent = { Text(organizationId, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.projectId?.let { projectId ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_project_label, "")) },
                                supportingContent = { Text(projectId, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.runStartNs?.let { runStartNs ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_run_start_ns_label, "")) },
                                supportingContent = { Text(runStartNs.toString(), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.templateId?.let { templateId ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_template_label, "")) },
                                supportingContent = { Text(templateId, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.baseTemplateId?.let { baseTemplateId ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_base_template_label, "")) },
                                supportingContent = { Text(baseTemplateId, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.numSteps?.let { numSteps ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_num_steps_label, "")) },
                                supportingContent = { Text(numSteps.toString(), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        it.runNs?.let { runNs ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_run_ns_label, "")) },
                                supportingContent = { Text(runNs.toString(), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        if (it.toolsUsed.isNotEmpty()) {
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_metrics_tools_used_label, "")) },
                                supportingContent = { Text(it.toolsUsed.joinToString(", "), style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                    }
                }
            }
            if (steps.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.screen_runs_steps_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(steps.take(5), key = { it.id }) { step ->
                    Card(
                        onClick = { onInspectStep(step.id) },
                        colors = LettaCardDefaults.listCardColors(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = buildString {
                                    append(step.id)
                                    step.status?.let { append(" - ").append(it) }
                                    step.model?.let { append(" - ").append(it) }
                                },
                                style = MaterialTheme.typography.listItemSupporting,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.screen_runs_step_inspect_hint),
                                style = MaterialTheme.typography.listItemMetadata,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            step.origin?.let { Text(text = it, style = MaterialTheme.typography.listItemSupporting) }
                            step.providerName?.let { Text(stringResource(R.string.screen_runs_step_provider_label, it), style = MaterialTheme.typography.listItemSupporting) }
                            step.providerCategory?.let { Text(stringResource(R.string.screen_runs_step_provider_category_label, it), style = MaterialTheme.typography.listItemSupporting) }
                            step.providerId?.let { Text(stringResource(R.string.screen_runs_step_provider_id_label, it), style = MaterialTheme.typography.listItemSupporting) }
                            step.modelEndpoint?.let { Text(stringResource(R.string.screen_runs_step_model_endpoint_label, it), style = MaterialTheme.typography.listItemSupporting, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            step.contextWindowLimit?.let { Text(stringResource(R.string.screen_runs_step_context_window_limit_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.promptTokens?.let { Text(stringResource(R.string.screen_runs_step_prompt_tokens_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.completionTokens?.let { Text(stringResource(R.string.screen_runs_step_completion_tokens_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.totalTokens?.let { Text(stringResource(R.string.screen_runs_step_total_tokens_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.traceId?.let { Text(stringResource(R.string.screen_runs_step_trace_id_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.tid?.let { Text(stringResource(R.string.screen_runs_step_tid_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            step.feedback?.let { Text(stringResource(R.string.screen_runs_step_feedback_label, it), style = MaterialTheme.typography.listItemMetadata) }
                            StepTagRow(tags = step.tags) { tag -> onTagClick(step.id, tag) }
                            step.errorType?.let { Text(stringResource(R.string.screen_runs_step_error_type_label, it), style = MaterialTheme.typography.listItemSupporting) }
                            if (step.messages.isNotEmpty()) {
                                Text(stringResource(R.string.screen_runs_step_messages_count_label, step.messages.size), style = MaterialTheme.typography.listItemMetadata)
                            }
                            if (step.completionTokensDetails.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.screen_runs_step_completion_details_label,
                                        step.completionTokensDetails.toSortedDisplayString(),
                                    ),
                                    style = MaterialTheme.typography.listItemSupporting,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (step.errorData.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.screen_runs_step_error_data_label,
                                        step.errorData.toSortedDisplayString(),
                                    ),
                                    style = MaterialTheme.typography.listItemSupporting,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (messages.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.screen_runs_messages_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(messages.takeLast(5), key = { it.id }) { message ->
                    Text(
                        text = stringResource(
                            R.string.screen_runs_message_entry,
                            message.messageType,
                            messageSummary(message),
                        ),
                        style = MaterialTheme.typography.listItemSupporting,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.action_cancel_run), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDetailDialog(
    step: Step,
    messages: List<LettaMessage>,
    metrics: StepMetrics?,
    trace: ProviderTrace?,
    onDismiss: () -> Unit,
    onTagClick: (String) -> Unit,
    onSetPositiveFeedback: () -> Unit,
    onSetNegativeFeedback: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = step.id,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1 — Core Status & Identity
            CardGroup(title = { Text(stringResource(R.string.screen_runs_step_section_core_title)) }) {
                step.status?.let { status ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_status_label, "").trim().trimEnd(':')) },
                        supportingContent = { StatusChip(status = status) },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.screen_runs_step_id_title)) },
                    supportingContent = {
                        Text(
                            text = step.id,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                        )
                    },
                )
                step.origin?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_origin_title)) },
                        supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                step.runId?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_run_id_title)) },
                        supportingContent = {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                            )
                        },
                    )
                }
                step.agentId?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_agent_id_title)) },
                        supportingContent = {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                            )
                        },
                    )
                }
                step.model?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_model_title)) },
                        supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                step.modelEndpoint?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_model_endpoint_title)) },
                        supportingContent = {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.listItemSupporting,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                step.contextWindowLimit?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_context_window_title)) },
                        supportingContent = { Text(value.toString(), style = MaterialTheme.typography.listItemMetadata) },
                    )
                }
                step.stopReason?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_stop_reason_title)) },
                        supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                step.feedback?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_feedback_title)) },
                        supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                step.traceId?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_trace_id_title)) },
                        supportingContent = {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                            )
                        },
                    )
                }
                step.tid?.let { value ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_runs_step_thread_id_title)) },
                        supportingContent = {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                            )
                        },
                    )
                }
            }

            // Section 2 — Token Telemetry
            val hasTelemetryGrid = step.promptTokens != null ||
                step.completionTokens != null ||
                metrics?.stepNs != null
            if (hasTelemetryGrid) {
                TelemetryGrid(
                    promptTokens = step.promptTokens ?: 0,
                    completionTokens = step.completionTokens ?: 0,
                    durationMs = metrics?.stepNs?.toWholeMilliseconds() ?: 0L,
                    costUsd = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val hasSupplementalTelemetry = step.totalTokens != null ||
                step.completionTokensDetails.isNotEmpty()
            if (hasSupplementalTelemetry) {
                CardGroup(title = { Text(stringResource(R.string.screen_runs_step_section_telemetry_title)) }) {
                    step.totalTokens?.let { value ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_total_tokens_title)) },
                            supportingContent = { Text(value.toString(), style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    if (step.completionTokensDetails.isNotEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_completion_details_title)) },
                            supportingContent = {
                                Text(
                                    text = step.completionTokensDetails.toSortedDisplayString(),
                                    style = MaterialTheme.typography.listItemSupporting,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // Provider & Metrics
            val hasProvider = step.providerName != null || step.providerCategory != null || step.providerId != null
            if (hasProvider || metrics != null) {
                CardGroup(title = { Text(stringResource(R.string.screen_runs_step_metrics_title)) }) {
                    step.providerName?.let { value ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_provider_title)) },
                            supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    step.providerCategory?.let { value ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_provider_category_title)) },
                            supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    step.providerId?.let { value ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_provider_id_title)) },
                            supportingContent = {
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                )
                            },
                        )
                    }
                    metrics?.let { m ->
                        m.stepStartNs?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_metrics_start_ns_title)) },
                                supportingContent = { Text(v.toString(), style = MaterialTheme.typography.listItemMetadata) },
                            )
                        }
                        m.llmRequestStartNs?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_metrics_llm_request_start_title)) },
                                supportingContent = { Text(v.toString(), style = MaterialTheme.typography.listItemMetadata) },
                            )
                        }
                        m.llmRequestNs?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_metrics_llm_request_title)) },
                                supportingContent = { Text(v.toString(), style = MaterialTheme.typography.listItemMetadata) },
                            )
                        }
                        m.toolExecutionNs?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_metrics_tool_execution_title)) },
                                supportingContent = { Text(v.toString(), style = MaterialTheme.typography.listItemMetadata) },
                            )
                        }
                        m.stepNs?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_metrics_step_duration_title)) },
                                supportingContent = { Text(v.toString(), style = MaterialTheme.typography.listItemMetadata) },
                            )
                        }
                        m.templateId?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_template_title)) },
                                supportingContent = { Text(v, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        m.baseTemplateId?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_base_template_title)) },
                                supportingContent = { Text(v, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        m.projectId?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_project_title)) },
                                supportingContent = { Text(v, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                        m.organizationId?.let { v ->
                            item(
                                headlineContent = { Text(stringResource(R.string.screen_runs_step_organization_title)) },
                                supportingContent = { Text(v, style = MaterialTheme.typography.listItemSupporting) },
                            )
                        }
                    }
                }
            }

            // Errors
            if (step.errorType != null || step.errorData.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.screen_runs_step_section_errors_title)) }) {
                    step.errorType?.let { value ->
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_error_type_title)) },
                            supportingContent = { Text(value, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    if (step.errorData.isNotEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_runs_step_error_data_title)) },
                            supportingContent = {
                                Text(
                                    text = step.errorData.toSortedDisplayString(),
                                    style = MaterialTheme.typography.listItemSupporting,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // Tags
            StepTagRow(tags = step.tags, onTagClick = onTagClick)

            // Section 3 — Developer Traces
            trace?.let { t ->
                Text(
                    text = stringResource(R.string.screen_runs_step_trace_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                t.createdAt?.let { createdAt ->
                    Text(
                        text = stringResource(R.string.screen_runs_step_trace_created_label, createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                StepTraceAccordion(
                    title = stringResource(R.string.screen_runs_step_trace_request_accordion_title),
                    json = t.requestJson,
                )
                StepTraceAccordion(
                    title = stringResource(R.string.screen_runs_step_trace_response_accordion_title),
                    json = t.responseJson,
                )
            }

            // Recent messages
            if (messages.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.screen_runs_step_messages_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                messages.takeLast(8).forEach { message ->
                    Text(
                        text = stringResource(R.string.screen_runs_message_entry, message.messageType, messageSummary(message)),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Footer — feedback actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSetPositiveFeedback) {
                    Text(stringResource(R.string.screen_runs_step_feedback_positive_action))
                }
                TextButton(onClick = onSetNegativeFeedback) {
                    Text(
                        text = stringResource(R.string.screen_runs_step_feedback_negative_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (step.feedback != null) {
                    TextButton(onClick = onClearFeedback) {
                        Text(stringResource(R.string.screen_runs_step_feedback_clear_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTraceAccordion(
    title: String,
    json: Map<String, JsonElement>,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val pretty = remember(json) { json.toPrettyJsonString() }
    val truncated = remember(pretty) { truncateJsonForDisplay(pretty) }

    Accordions(
        title = title,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        subtitle = stringResource(R.string.screen_runs_step_trace_size_subtitle, truncated.totalLength),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (truncated.isTruncated) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.screen_runs_step_trace_truncation_warning,
                            STEP_TRACE_DISPLAY_LIMIT,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(pretty))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    ) {
                        Text(stringResource(R.string.screen_runs_step_trace_copy_full))
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = truncated.displayed,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private const val STEP_TRACE_DISPLAY_LIMIT = 25_000

private data class TruncatedJson(
    val displayed: String,
    val isTruncated: Boolean,
    val totalLength: Int,
)

private fun truncateJsonForDisplay(json: String): TruncatedJson {
    return if (json.length > STEP_TRACE_DISPLAY_LIMIT) {
        val overflow = json.length - STEP_TRACE_DISPLAY_LIMIT
        TruncatedJson(
            displayed = json.take(STEP_TRACE_DISPLAY_LIMIT) + "\n\n… [TRUNCATED — $overflow more chars] …",
            isTruncated = true,
            totalLength = json.length,
        )
    } else {
        TruncatedJson(displayed = json, isTruncated = false, totalLength = json.length)
    }
}

private val stepTracePrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun Map<String, JsonElement>.toPrettyJsonString(): String =
    if (isEmpty()) "{}" else stepTracePrettyJson.encodeToString(JsonObject.serializer(), JsonObject(this))

@Composable
private fun StepTagRow(
    tags: List<String>,
    onTagClick: (String) -> Unit,
) {
    if (tags.isEmpty()) return

    Text(
        text = stringResource(R.string.common_tags),
        style = MaterialTheme.typography.listItemMetadata,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { onTagClick(tag) },
                label = { Text(tag) },
            )
        }
    }
}

private fun Map<String, JsonElement>.toSortedDisplayString(): String {
    return entries.sortedBy { it.key }.joinToString(", ") { (key, value) -> "$key=${value.toDisplayString()}" }
}

private fun Long.toWholeMilliseconds(): Long = this / 1_000_000L

private fun JsonElement.toDisplayString(): String = toString().trim('"')

private fun messageSummary(message: LettaMessage): String {
    return when (message) {
        is com.letta.mobile.data.model.SystemMessage -> message.content
        is com.letta.mobile.data.model.UserMessage -> message.content
        is com.letta.mobile.data.model.AssistantMessage -> message.content
        is com.letta.mobile.data.model.ReasoningMessage -> message.reasoning
        is com.letta.mobile.data.model.ToolCallMessage -> message.effectiveToolCalls.firstOrNull()?.name.orEmpty()
        is com.letta.mobile.data.model.ToolReturnMessage -> message.toolReturn.funcResponse.orEmpty()
        is com.letta.mobile.data.model.ApprovalRequestMessage -> message.effectiveToolCalls.joinToString { it.name.orEmpty() }
        is com.letta.mobile.data.model.ApprovalResponseMessage -> message.approvals?.joinToString { it.status.orEmpty() }.orEmpty()
        is com.letta.mobile.data.model.HiddenReasoningMessage -> message.hiddenReasoning.orEmpty()
        is com.letta.mobile.data.model.EventMessage -> message.eventType
        is com.letta.mobile.data.model.PingMessage -> message.messageType
        is com.letta.mobile.data.model.UnknownMessage -> message.messageType
        is com.letta.mobile.data.model.ErrorMessage -> "Error: ${message.text.take(80)}"
        is com.letta.mobile.data.model.StopReason -> "Stop: ${message.reason}"
        is com.letta.mobile.data.model.UsageStatistics -> "Usage: ${message.totalTokens ?: 0} tokens"
    }
}

private fun Run.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}

@PreviewLightDark
@Composable
private fun PreviewRunCardRunning() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RunCard(
                    run = Run(
                        id = "run-01HXYZK7QJ4R3M8WJ0NABCDEF9G",
                        agentId = "agent-research-01",
                        status = "running",
                        background = false,
                        conversationId = "conv-7d4c2e1f",
                        createdAt = Instant.now().minusSeconds(73).toString(),
                    ),
                    onInspect = {},
                )
                RunCard(
                    run = Run(
                        id = "run-01HXYZK7QJ4R3M8WJ0NABCDEFFAILED",
                        agentId = "agent-coder-02",
                        status = "failed",
                        createdAt = Instant.now().minusSeconds(3725).toString(),
                        completedAt = Instant.now().minusSeconds(3600).toString(),
                        totalDurationNs = 125_000_000_000L,
                    ),
                    onInspect = {},
                )
                RunCard(
                    run = Run(
                        id = "run-01HXYZK7QJ4R3M8WJ0NABCDEFCOMPLETED",
                        agentId = "agent-summarizer-09",
                        status = "completed",
                        createdAt = Instant.now().minusSeconds(86400).toString(),
                        completedAt = Instant.now().minusSeconds(86340).toString(),
                        totalDurationNs = 59_000_000_000L,
                    ),
                    onInspect = {},
                )
            }
        }
    }
}
