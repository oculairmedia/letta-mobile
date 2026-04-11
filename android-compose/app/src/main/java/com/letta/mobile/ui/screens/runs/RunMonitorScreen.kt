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
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.util.formatRelativeTime
import kotlinx.serialization.json.JsonElement
import com.letta.mobile.ui.icons.LettaIcons

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
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_runs_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
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
                        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
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
            onInspectStep = viewModel::inspectStep,
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

    val selectedStep = (uiState as? UiState.Success)?.data?.selectedStep
    selectedStep?.let { step ->
        val state = (uiState as? UiState.Success)?.data
        StepDetailDialog(
            step = step,
            messages = state?.selectedStepMessages.orEmpty(),
            metrics = state?.selectedStepMetrics,
            trace = state?.selectedStepTrace,
            onDismiss = { viewModel.clearSelectedStep() },
            onSetPositiveFeedback = { viewModel.updateStepFeedback(step.id, "positive") },
            onSetNegativeFeedback = { viewModel.updateStepFeedback(step.id, "negative") },
            onClearFeedback = { viewModel.updateStepFeedback(step.id, null) },
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
                    imageVector = LettaIcons.Delete,
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
    steps: List<Step>,
    onInspectStep: (String) -> Unit,
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
                run.callbackSentAt?.let { Text(stringResource(R.string.screen_runs_callback_sent_label, it)) }
                run.callbackStatusCode?.let { Text(stringResource(R.string.screen_runs_callback_status_label, it)) }
                run.callbackError?.let { Text(stringResource(R.string.screen_runs_callback_error_label, it)) }
                run.totalDurationNs?.let { Text(stringResource(R.string.screen_runs_total_duration_label, it)) }
                run.ttftNs?.let { Text(stringResource(R.string.screen_runs_ttft_label, it)) }
                if (run.metadata.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_metadata_title), style = MaterialTheme.typography.labelLarge)
                    run.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        Text(
                            text = "$key: ${value.toDisplayString()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                run.requestConfig?.let { config ->
                    Text(stringResource(R.string.screen_runs_request_config_title), style = MaterialTheme.typography.labelLarge)
                    config.assistantMessageToolName?.let {
                        Text(stringResource(R.string.screen_runs_request_config_assistant_tool_name_label, it))
                    }
                    config.assistantMessageToolKwarg?.let {
                        Text(stringResource(R.string.screen_runs_request_config_assistant_tool_kwarg_label, it))
                    }
                    config.includeReturnMessageTypes?.takeIf { it.isNotEmpty() }?.let {
                        Text(stringResource(R.string.screen_runs_request_config_include_return_types_label, it.joinToString(", ")))
                    }
                    config.useAssistantMessage?.let {
                        Text(stringResource(R.string.screen_runs_request_config_use_assistant_message_label, it.toString()))
                    }
                }
                usage?.let {
                    Text(stringResource(R.string.screen_runs_usage_title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.screen_runs_usage_prompt_tokens_label, it.promptTokens ?: 0))
                    Text(stringResource(R.string.screen_runs_usage_completion_tokens_label, it.completionTokens ?: 0))
                    Text(stringResource(R.string.screen_runs_usage_total_tokens_label, it.totalTokens ?: 0))
                }
                metrics?.let {
                    Text(stringResource(R.string.screen_runs_metrics_title), style = MaterialTheme.typography.labelLarge)
                    it.organizationId?.let { organizationId ->
                        Text(stringResource(R.string.screen_runs_metrics_organization_label, organizationId))
                    }
                    it.projectId?.let { projectId ->
                        Text(stringResource(R.string.screen_runs_metrics_project_label, projectId))
                    }
                    it.runStartNs?.let { runStartNs ->
                        Text(stringResource(R.string.screen_runs_metrics_run_start_ns_label, runStartNs))
                    }
                    it.templateId?.let { templateId ->
                        Text(stringResource(R.string.screen_runs_metrics_template_label, templateId))
                    }
                    it.baseTemplateId?.let { baseTemplateId ->
                        Text(stringResource(R.string.screen_runs_metrics_base_template_label, baseTemplateId))
                    }
                    it.numSteps?.let { numSteps -> Text(stringResource(R.string.screen_runs_metrics_num_steps_label, numSteps)) }
                    it.runNs?.let { runNs -> Text(stringResource(R.string.screen_runs_metrics_run_ns_label, runNs)) }
                    if (it.toolsUsed.isNotEmpty()) {
                        Text(stringResource(R.string.screen_runs_metrics_tools_used_label, it.toolsUsed.joinToString(", ")))
                    }
                }
                if (steps.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_steps_title), style = MaterialTheme.typography.labelLarge)
                    steps.take(5).forEach { step ->
                        Card(onClick = { onInspectStep(step.id) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
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
                            Text(
                                text = stringResource(R.string.screen_runs_step_inspect_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            step.origin?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                            step.providerName?.let { Text(stringResource(R.string.screen_runs_step_provider_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.providerCategory?.let { Text(stringResource(R.string.screen_runs_step_provider_category_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.providerId?.let { Text(stringResource(R.string.screen_runs_step_provider_id_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.modelEndpoint?.let { Text(stringResource(R.string.screen_runs_step_model_endpoint_label, it), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            step.contextWindowLimit?.let { Text(stringResource(R.string.screen_runs_step_context_window_limit_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.promptTokens?.let { Text(stringResource(R.string.screen_runs_step_prompt_tokens_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.completionTokens?.let { Text(stringResource(R.string.screen_runs_step_completion_tokens_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.totalTokens?.let { Text(stringResource(R.string.screen_runs_step_total_tokens_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.traceId?.let { Text(stringResource(R.string.screen_runs_step_trace_id_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.tid?.let { Text(stringResource(R.string.screen_runs_step_tid_label, it), style = MaterialTheme.typography.bodySmall) }
                            step.feedback?.let { Text(stringResource(R.string.screen_runs_step_feedback_label, it), style = MaterialTheme.typography.bodySmall) }
                            if (step.tags.isNotEmpty()) {
                                Text(stringResource(R.string.screen_runs_step_tags_label, step.tags.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                            step.errorType?.let { Text(stringResource(R.string.screen_runs_step_error_type_label, it), style = MaterialTheme.typography.bodySmall) }
                            if (step.messages.isNotEmpty()) {
                                Text(stringResource(R.string.screen_runs_step_messages_count_label, step.messages.size), style = MaterialTheme.typography.bodySmall)
                            }
                            if (step.completionTokensDetails.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.screen_runs_step_completion_details_label,
                                        step.completionTokensDetails.toSortedDisplayString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
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
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            }
                        }
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

@Composable
private fun StepDetailDialog(
    step: Step,
    messages: List<LettaMessage>,
    metrics: StepMetrics?,
    trace: ProviderTrace?,
    onDismiss: () -> Unit,
    onSetPositiveFeedback: () -> Unit,
    onSetNegativeFeedback: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(step.id, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                step.status?.let { Text(stringResource(R.string.screen_runs_status_label, it)) }
                step.feedback?.let { Text(stringResource(R.string.screen_runs_step_feedback_label, it)) }
                step.origin?.let { Text(stringResource(R.string.screen_runs_step_origin_label, it)) }
                step.runId?.let { Text(stringResource(R.string.screen_runs_step_run_label, it)) }
                step.agentId?.let { Text(stringResource(R.string.screen_runs_agent_label, it)) }
                step.providerName?.let { Text(stringResource(R.string.screen_runs_step_provider_label, it)) }
                step.providerCategory?.let { Text(stringResource(R.string.screen_runs_step_provider_category_label, it)) }
                step.providerId?.let { Text(stringResource(R.string.screen_runs_step_provider_id_label, it)) }
                step.model?.let { Text(stringResource(R.string.screen_runs_step_model_label, it)) }
                step.modelEndpoint?.let { Text(stringResource(R.string.screen_runs_step_model_endpoint_label, it)) }
                step.contextWindowLimit?.let { Text(stringResource(R.string.screen_runs_step_context_window_limit_label, it)) }
                step.promptTokens?.let { Text(stringResource(R.string.screen_runs_step_prompt_tokens_label, it)) }
                step.completionTokens?.let { Text(stringResource(R.string.screen_runs_step_completion_tokens_label, it)) }
                step.totalTokens?.let { Text(stringResource(R.string.screen_runs_step_total_tokens_label, it)) }
                step.traceId?.let { Text(stringResource(R.string.screen_runs_step_trace_id_label, it)) }
                step.tid?.let { Text(stringResource(R.string.screen_runs_step_tid_label, it)) }
                step.stopReason?.let { Text(stringResource(R.string.screen_runs_stop_reason_label, it)) }
                if (step.tags.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_step_tags_label, step.tags.joinToString(", ")))
                }
                step.errorType?.let { Text(stringResource(R.string.screen_runs_step_error_type_label, it)) }
                if (step.completionTokensDetails.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_step_completion_details_label, step.completionTokensDetails.toSortedDisplayString()))
                }
                if (step.errorData.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_step_error_data_label, step.errorData.toSortedDisplayString()))
                }
                metrics?.let {
                    Text(stringResource(R.string.screen_runs_step_metrics_title), style = MaterialTheme.typography.labelLarge)
                    it.organizationId?.let { value -> Text(stringResource(R.string.screen_runs_metrics_organization_label, value)) }
                    it.providerId?.let { value -> Text(stringResource(R.string.screen_runs_step_provider_id_label, value)) }
                    it.runId?.let { value -> Text(stringResource(R.string.screen_runs_step_run_label, value)) }
                    it.agentId?.let { value -> Text(stringResource(R.string.screen_runs_agent_label, value)) }
                    it.stepStartNs?.let { value -> Text(stringResource(R.string.screen_runs_step_metrics_start_ns_label, value)) }
                    it.llmRequestStartNs?.let { value -> Text(stringResource(R.string.screen_runs_step_metrics_llm_request_start_label, value)) }
                    it.llmRequestNs?.let { value -> Text(stringResource(R.string.screen_runs_step_metrics_llm_request_label, value)) }
                    it.toolExecutionNs?.let { value -> Text(stringResource(R.string.screen_runs_step_metrics_tool_execution_label, value)) }
                    it.stepNs?.let { value -> Text(stringResource(R.string.screen_runs_step_metrics_step_ns_label, value)) }
                    it.templateId?.let { value -> Text(stringResource(R.string.screen_runs_metrics_template_label, value)) }
                    it.baseTemplateId?.let { value -> Text(stringResource(R.string.screen_runs_metrics_base_template_label, value)) }
                    it.projectId?.let { value -> Text(stringResource(R.string.screen_runs_metrics_project_label, value)) }
                }
                trace?.let {
                    Text(stringResource(R.string.screen_runs_step_trace_title), style = MaterialTheme.typography.labelLarge)
                    it.createdAt?.let { value -> Text(stringResource(R.string.screen_runs_step_trace_created_label, value)) }
                    if (it.requestJson.isNotEmpty()) {
                        Text(stringResource(R.string.screen_runs_step_trace_request_label, it.requestJson.toSortedDisplayString()))
                    }
                    if (it.responseJson.isNotEmpty()) {
                        Text(stringResource(R.string.screen_runs_step_trace_response_label, it.responseJson.toSortedDisplayString()))
                    }
                }
                if (messages.isNotEmpty()) {
                    Text(stringResource(R.string.screen_runs_step_messages_title), style = MaterialTheme.typography.labelLarge)
                    messages.takeLast(8).forEach { message ->
                        Text(
                            text = stringResource(R.string.screen_runs_message_entry, message.messageType, messageSummary(message)),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSetPositiveFeedback) {
                    Text(stringResource(R.string.screen_runs_step_feedback_positive_action))
                }
                TextButton(onClick = onSetNegativeFeedback) {
                    Text(stringResource(R.string.screen_runs_step_feedback_negative_action), color = MaterialTheme.colorScheme.error)
                }
                if (step.feedback != null) {
                    TextButton(onClick = onClearFeedback) {
                        Text(stringResource(R.string.screen_runs_step_feedback_clear_action))
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

private fun Map<String, JsonElement>.toSortedDisplayString(): String {
    return entries.sortedBy { it.key }.joinToString(", ") { (key, value) -> "$key=${value.toDisplayString()}" }
}

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
    }
}

private fun Run.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}
