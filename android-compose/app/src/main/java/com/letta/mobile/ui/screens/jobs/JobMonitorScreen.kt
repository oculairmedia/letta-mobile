package com.letta.mobile.ui.screens.jobs

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
import com.letta.mobile.ui.components.ExpandableTitleSearch
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.letta.mobile.data.model.Job
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobMonitorScreen(
    onNavigateBack: () -> Unit,
    viewModel: JobMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cancelTarget by remember { mutableStateOf<Job?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Job?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_jobs_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_jobs_title)) },
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
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadJobs() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredJobs = remember(state.data.jobs, state.data.searchQuery) {
                    viewModel.getFilteredJobs()
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
                        Text(stringResource(R.string.screen_jobs_active_only), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.data.activeOnly,
                            onCheckedChange = viewModel::toggleActiveOnly,
                        )
                    }

                    if (filteredJobs.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.AccessTime,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_jobs_empty)
                            } else {
                                stringResource(R.string.screen_jobs_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredJobs, key = { it.id }) { job ->
                                JobCard(
                                    job = job,
                                    onInspect = { viewModel.inspectJob(job.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedJob = (uiState as? UiState.Success)?.data?.selectedJob
    selectedJob?.let { job ->
        JobDetailDialog(
            job = job,
            onDismiss = { viewModel.clearSelectedJob() },
            onCancel = if (job.isTerminalStatus()) null else {
                {
                    viewModel.clearSelectedJob()
                    cancelTarget = job
                }
            },
            onDelete = if (job.isTerminalStatus()) {
                {
                    viewModel.clearSelectedJob()
                    deleteTarget = job
                }
            } else null,
        )
    }

    cancelTarget?.let { job ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_jobs_cancel_title),
            message = stringResource(R.string.screen_jobs_cancel_confirm, job.id),
            confirmText = stringResource(R.string.action_cancel_job),
            dismissText = stringResource(R.string.action_close),
            onConfirm = {
                viewModel.cancelJob(job.id)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
            destructive = true,
        )
    }

    deleteTarget?.let { job ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_jobs_delete_title),
            message = stringResource(R.string.screen_jobs_delete_confirm, job.id),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_close),
            onConfirm = {
                viewModel.deleteJob(job.id)
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
private fun JobCard(
    job: Job,
    onInspect: () -> Unit,
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = job.id,
                    style = MaterialTheme.typography.listItemHeadline.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                if (job.isTerminalStatus()) {
                    Icon(
                        imageVector = LettaIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                job.status?.let { status ->
                    AssistChip(onClick = {}, label = { Text(status) })
                }
                job.jobType?.let { type ->
                    AssistChip(onClick = {}, label = { Text(type) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            job.agentId?.let { agentId ->
                Text(
                    text = stringResource(R.string.screen_jobs_agent_label, agentId),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            job.userId?.let { userId ->
                Text(
                    text = stringResource(R.string.screen_jobs_user_label, userId),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            job.createdAt?.let { createdAt ->
                Text(
                    text = stringResource(R.string.screen_jobs_created_label, formatRelativeTime(createdAt)),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JobDetailDialog(
    job: Job,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(job.id, style = MaterialTheme.typography.listItemHeadline.copy(fontFamily = FontFamily.Monospace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                job.status?.let { Text(stringResource(R.string.screen_jobs_status_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.jobType?.let { Text(stringResource(R.string.screen_jobs_type_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.stopReason?.let { Text(stringResource(R.string.screen_jobs_stop_reason_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.agentId?.let { Text(stringResource(R.string.screen_jobs_agent_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.userId?.let { Text(stringResource(R.string.screen_jobs_user_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.createdAt?.let { Text(stringResource(R.string.screen_jobs_created_exact_label, it), style = MaterialTheme.typography.listItemMetadata) }
                job.completedAt?.let { Text(stringResource(R.string.screen_jobs_completed_label, it), style = MaterialTheme.typography.listItemMetadata) }
                job.callbackUrl?.let { Text(stringResource(R.string.screen_jobs_callback_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.callbackSentAt?.let { Text(stringResource(R.string.screen_jobs_callback_sent_at_label, it), style = MaterialTheme.typography.listItemMetadata) }
                job.callbackStatusCode?.let { Text(stringResource(R.string.screen_jobs_callback_status_label, it), style = MaterialTheme.typography.listItemMetadata) }
                job.callbackError?.let { Text(stringResource(R.string.screen_jobs_callback_error_label, it), style = MaterialTheme.typography.listItemSupporting) }
                job.totalDurationNs?.let { Text(stringResource(R.string.screen_jobs_total_duration_label, it), style = MaterialTheme.typography.listItemMetadata) }
                job.ttftNs?.let { Text(stringResource(R.string.screen_jobs_ttft_label, it), style = MaterialTheme.typography.listItemMetadata) }
                if (job.metadata.isNotEmpty()) {
                    Text(stringResource(R.string.screen_jobs_metadata_title), style = MaterialTheme.typography.dialogSectionHeading)
                    job.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        Text(
                            text = "$key: $value",
                            style = MaterialTheme.typography.listItemSupporting,
                            maxLines = 4,
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
                        Text(stringResource(R.string.action_cancel_job), color = MaterialTheme.colorScheme.error)
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

private fun Job.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}
