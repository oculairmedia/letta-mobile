package com.letta.mobile.ui.screens.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.RunStep
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunMonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: RunMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cancelTarget by remember { mutableStateOf<Run?>(null) }
    var deleteTarget by remember { mutableStateOf<Run?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_runs_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
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
                    OutlinedTextField(
                        value = state.data.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.screen_runs_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )

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
                            icon = Icons.Default.AccessTime,
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
                            items(filteredRuns, key = { it.id }) { run ->
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

    val selectedRun = (uiState as? UiState.Success)?.data?.selectedRun
    selectedRun?.let { run ->
        val state = (uiState as? UiState.Success)?.data
        RunDetailDialog(
            run = run,
            messages = state?.selectedRunMessages.orEmpty(),
            usage = state?.selectedRunUsage,
            metrics = state?.selectedRunMetrics,
            steps = state?.selectedRunSteps.orEmpty(),
            onDismiss = { viewModel.clearSelectedRun() },
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
        AlertDialog(
            onDismissRequest = { viewModel.clearOperationError() },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(operationError) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearOperationError() }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun RunCard(
    run: Run,
    onInspect: () -> Unit,
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = run.id,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                run.status?.let { status ->
                    AssistChip(onClick = {}, label = { Text(status) })
                }
                if (run.background == true) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.screen_runs_background_chip)) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.screen_runs_agent_label, run.agentId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            run.conversationId?.let { conversationId ->
                Text(
                    text = stringResource(R.string.screen_runs_conversation_label, conversationId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            run.createdAt?.let { createdAt ->
                Text(
                    text = stringResource(R.string.screen_runs_created_label, formatRelativeTime(createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run.isTerminalStatus()) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunDetailDialog(
    run: Run,
    messages: List<LettaMessage>,
    usage: UsageStatistics?,
    metrics: RunMetrics?,
    steps: List<RunStep>,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(run.id, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run.status?.let { Text(stringResource(R.string.screen_runs_status_label, it)) }
                run.stopReason?.let { Text(stringResource(R.string.screen_runs_stop_reason_label, it)) }
                Text(stringResource(R.string.screen_runs_agent_label, run.agentId))
                run.conversationId?.let { Text(stringResource(R.string.screen_runs_conversation_label, it)) }
                run.createdAt?.let { Text(stringResource(R.string.screen_runs_created_exact_label, it)) }
                run.completedAt?.let { Text(stringResource(R.string.screen_runs_completed_label, it)) }
                run.callbackUrl?.let { Text(stringResource(R.string.screen_runs_callback_label, it)) }
                run.callbackStatusCode?.let { Text(stringResource(R.string.screen_runs_callback_status_label, it)) }
                run.totalDurationNs?.let { Text(stringResource(R.string.screen_runs_total_duration_label, it)) }
                run.ttftNs?.let { Text(stringResource(R.string.screen_runs_ttft_label, it)) }
                usage?.let {
                    Text(stringResource(R.string.screen_runs_usage_title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.screen_runs_usage_prompt_tokens_label, it.promptTokens ?: 0))
                    Text(stringResource(R.string.screen_runs_usage_completion_tokens_label, it.completionTokens ?: 0))
                    Text(stringResource(R.string.screen_runs_usage_total_tokens_label, it.totalTokens ?: 0))
                }
                metrics?.let {
                    Text(stringResource(R.string.screen_runs_metrics_title), style = MaterialTheme.typography.labelLarge)
                    it.numSteps?.let { numSteps -> Text(stringResource(R.string.screen_runs_metrics_num_steps_label, numSteps)) }
                    it.runNs?.let { runNs -> Text(stringResource(R.string.screen_runs_metrics_run_ns_label, runNs)) }
                    if (it.toolsUsed.isNotEmpty()) {
                        Text(stringResource(R.string.screen_runs_metrics_tools_used_label, it.toolsUsed.joinToString(", ")))
                    }
                }
                if (steps.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_steps_title), style = MaterialTheme.typography.labelLarge)
                    steps.take(5).forEach { step ->
                        Text(
                            text = buildString {
                                append(step.id)
                                step.status?.let { append(" • ").append(it) }
                                step.model?.let { append(" • ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (messages.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_messages_title), style = MaterialTheme.typography.labelLarge)
                    messages.takeLast(5).forEach { message ->
                        Text(
                            text = stringResource(
                                R.string.screen_runs_message_entry,
                                message.messageType,
                                messageSummary(message),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
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
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

private fun messageSummary(message: LettaMessage): String {
    return when (message) {
        is com.letta.mobile.data.model.UserMessage -> message.content
        is com.letta.mobile.data.model.AssistantMessage -> message.content
        is com.letta.mobile.data.model.ReasoningMessage -> message.reasoning
        is com.letta.mobile.data.model.ToolCallMessage -> message.toolCall.name
        is com.letta.mobile.data.model.ToolReturnMessage -> message.toolReturn.funcResponse.orEmpty()
        is com.letta.mobile.data.model.ApprovalRequestMessage -> message.toolCalls?.joinToString { it.name }.orEmpty()
        is com.letta.mobile.data.model.ApprovalResponseMessage -> message.approvals?.joinToString { it.status.orEmpty() }.orEmpty()
        is com.letta.mobile.data.model.HiddenReasoningMessage -> message.hiddenReasoning.orEmpty()
        is com.letta.mobile.data.model.EventMessage -> message.eventType
        is com.letta.mobile.data.model.UnknownMessage -> message.messageType
    }
}

private fun Run.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}
