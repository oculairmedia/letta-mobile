package com.letta.mobile.ui.screens.messagebatches

import com.letta.mobile.ui.theme.LettaCodeFont

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.StatusChip
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.util.formatRelativeTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBatchMonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: MessageBatchMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cancelTarget by remember { mutableStateOf<Job?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

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
                        placeholder = stringResource(R.string.screen_message_batches_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_message_batches_title)) },
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
                placeholder = stringResource(R.string.screen_message_batches_search_hint),
            )
            }
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
                            icon = LettaIcons.ChatOutline,
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
private fun MessageBatchCard(
    batch: Job,
    onInspect: () -> Unit,
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = batch.id,
                style = MaterialTheme.typography.listItemHeadline.copy(fontFamily = LettaCodeFont),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                batch.status?.let { status ->
                    StatusChip(status = status)
                }
                batch.jobType?.let { type ->
                    AssistChip(onClick = {}, label = { Text(type) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            batch.createdAt?.let { createdAt ->
                Text(
                    text = stringResource(R.string.screen_message_batches_created_label, formatRelativeTime(createdAt)),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            batch.agentId?.let { agentId ->
                Text(
                    text = stringResource(R.string.screen_message_batches_agent_label, agentId),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            batch.stopReason?.let { stopReason ->
                Text(
                    text = stringResource(R.string.screen_message_batches_stop_reason_label, stopReason),
                    style = MaterialTheme.typography.listItemSupporting,
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
    ConfirmDialog(
        show = true,
        title = batch.id,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup {
                    batch.status?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_status_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.jobType?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_type_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.stopReason?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_stop_reason_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.agentId?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_agent_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.userId?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_user_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.createdAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_created_exact_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    batch.completedAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_completed_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    batch.callbackUrl?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_callback_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.callbackSentAt?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_callback_sent_at_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    batch.callbackStatusCode?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_callback_status_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    batch.callbackError?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_callback_error_label, "")) },
                            supportingContent = { Text(it, style = MaterialTheme.typography.listItemSupporting) },
                        )
                    }
                    batch.totalDurationNs?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_total_duration_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                    batch.ttftNs?.let {
                        item(
                            headlineContent = { Text(stringResource(R.string.screen_message_batches_ttft_label, "")) },
                            supportingContent = { Text(it.toString(), style = MaterialTheme.typography.listItemMetadata) },
                        )
                    }
                }
            }
            if (batch.metadata.isNotEmpty()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.screen_message_batches_metadata_title)) }) {
                        batch.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
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
            if (onCancel != null) {
                item {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel_batch), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.screen_message_batches_messages_title),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.screen_message_batches_messages_empty),
                        style = MaterialTheme.typography.listItemSupporting,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    BatchMessageCard(message = message)
                }
            }
        }
    }
}

@Composable
private fun BatchMessageCard(message: BatchMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
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
                    style = MaterialTheme.typography.listItemMetadata,
                )
                message.createdAt?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.listItemMetadata,
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
                style = MaterialTheme.typography.listItemSupporting,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            message.agentId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_agent_label, it),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.runId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_run_label, it),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.stepId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_step_label, it),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.batchItemId?.let {
                Text(
                    text = stringResource(R.string.screen_message_batches_message_batch_item_label, it),
                    style = MaterialTheme.typography.listItemMetadata,
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
