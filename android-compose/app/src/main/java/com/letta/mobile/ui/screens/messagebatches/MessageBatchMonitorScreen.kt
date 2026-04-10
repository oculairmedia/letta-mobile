package com.letta.mobile.ui.screens.messagebatches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChatBubbleOutline
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
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.util.formatRelativeTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBatchMonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: MessageBatchMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cancelTarget by remember { mutableStateOf<Job?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_message_batches_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
                onRetry = viewModel::loadBatches,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredBatches = remember(
                    state.data.batches,
                    state.data.searchQuery,
                    state.data.activeOnly,
                ) {
                    viewModel.getFilteredBatches()
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
                        placeholder = { Text(stringResource(R.string.screen_message_batches_search_hint)) },
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
                        Text(
                            stringResource(R.string.screen_message_batches_active_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = state.data.activeOnly,
                            onCheckedChange = viewModel::toggleActiveOnly,
                        )
                    }

                    if (filteredBatches.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.ChatBubbleOutline,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_message_batches_empty)
                            } else {
                                stringResource(R.string.screen_message_batches_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredBatches, key = { it.id }) { batch ->
                                MessageBatchCard(
                                    batch = batch,
                                    onInspect = { viewModel.inspectBatch(batch.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedBatch = (uiState as? UiState.Success)?.data?.selectedBatch
    selectedBatch?.let { batch ->
        MessageBatchDetailDialog(
            batch = batch,
            messages = (uiState as? UiState.Success)?.data?.selectedBatchMessages.orEmpty(),
            onDismiss = { viewModel.clearSelectedBatch() },
            onCancel = if (batch.isTerminalStatus()) null else {
                {
                    viewModel.clearSelectedBatch()
                    cancelTarget = batch
                }
            },
        )
    }

    cancelTarget?.let { batch ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_message_batches_cancel_title),
            message = stringResource(R.string.screen_message_batches_cancel_confirm, batch.id),
            confirmText = stringResource(R.string.action_cancel_batch),
            dismissText = stringResource(R.string.action_close),
            onConfirm = {
                viewModel.cancelBatch(batch.id)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
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
private fun MessageBatchCard(
    batch: Job,
    onInspect: () -> Unit,
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = batch.id,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                batch.status?.let { status ->
                    AssistChip(onClick = {}, label = { Text(status) })
                }
                batch.jobType?.let { type ->
                    AssistChip(onClick = {}, label = { Text(type) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            batch.createdAt?.let { createdAt ->
                Text(
                    text = stringResource(R.string.screen_message_batches_created_label, formatRelativeTime(createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            batch.agentId?.let { agentId ->
                Text(
                    text = stringResource(R.string.screen_message_batches_agent_label, agentId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            batch.stopReason?.let { stopReason ->
                Text(
                    text = stringResource(R.string.screen_message_batches_stop_reason_label, stopReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageBatchDetailDialog(
    batch: Job,
    messages: List<BatchMessage>,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(batch.id, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                batch.status?.let { Text(stringResource(R.string.screen_message_batches_status_label, it)) }
                batch.jobType?.let { Text(stringResource(R.string.screen_message_batches_type_label, it)) }
                batch.stopReason?.let { Text(stringResource(R.string.screen_message_batches_stop_reason_label, it)) }
                batch.agentId?.let { Text(stringResource(R.string.screen_message_batches_agent_label, it)) }
                batch.userId?.let { Text(stringResource(R.string.screen_message_batches_user_label, it)) }
                batch.createdAt?.let { Text(stringResource(R.string.screen_message_batches_created_exact_label, it)) }
                batch.completedAt?.let { Text(stringResource(R.string.screen_message_batches_completed_label, it)) }
                batch.callbackUrl?.let { Text(stringResource(R.string.screen_message_batches_callback_label, it)) }
                batch.callbackSentAt?.let { Text(stringResource(R.string.screen_message_batches_callback_sent_at_label, it)) }
                batch.callbackStatusCode?.let { Text(stringResource(R.string.screen_message_batches_callback_status_label, it)) }
                batch.callbackError?.let { Text(stringResource(R.string.screen_message_batches_callback_error_label, it)) }
                batch.totalDurationNs?.let { Text(stringResource(R.string.screen_message_batches_total_duration_label, it)) }
                batch.ttftNs?.let { Text(stringResource(R.string.screen_message_batches_ttft_label, it)) }
                if (batch.metadata.isNotEmpty()) {
                    Text(
                        stringResource(R.string.screen_message_batches_metadata_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    batch.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        Text(
                            text = "$key: ${value.toDisplayString()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    stringResource(R.string.screen_message_batches_messages_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (messages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_message_batches_messages_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            BatchMessageCard(message = message)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel_batch), color = MaterialTheme.colorScheme.error)
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
private fun BatchMessageCard(message: BatchMessage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.role ?: stringResource(R.string.screen_message_batches_message_unknown_role),
                    style = MaterialTheme.typography.labelMedium,
                )
                message.createdAt?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = message.content.toDisplayString().ifBlank {
                    message.toolCalls.toDisplayString().ifBlank {
                        message.toolReturns.toDisplayString().ifBlank {
                            stringResource(R.string.screen_message_batches_message_empty)
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            message.agentId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_agent_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.runId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_run_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.stepId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_step_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.batchItemId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_batch_item_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun JsonElement?.toDisplayString(): String {
    return when (this) {
        null -> ""
        is JsonPrimitive -> content
        is JsonArray -> joinToString("\n") { element -> element.toDisplayString() }
        is JsonObject -> entries.joinToString(", ") { (key, value) -> "$key=${value.toDisplayString()}" }
        else -> toString()
    }
}

private fun Job.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}
