package com.letta.mobile.ui.screens.schedules

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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScheduleListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val successState = uiState as? UiState.Success
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduledMessage?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (successState?.data?.scheduleAdminAvailable != false) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(LettaIcons.Add, stringResource(R.string.screen_schedules_add_title))
                }
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadData() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> ScheduleListContent(
                state = state.data,
                onAgentSelected = viewModel::selectAgent,
                onDeleteSchedule = { deleteTarget = it },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    val state = successState?.data
    if (showCreateDialog && state != null && state.scheduleAdminAvailable) {
        CreateScheduleDialog(
            agents = state.agents,
            selectedAgentId = state.selectedAgentId,
            onDismiss = { showCreateDialog = false },
            onCreate = { agentId, params ->
                viewModel.createSchedule(agentId, params)
                showCreateDialog = false
            },
        )
    }

    deleteTarget?.let { schedule ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.screen_schedules_delete_title),
            message = stringResource(R.string.screen_schedules_delete_confirm, schedule.id),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteSchedule(schedule.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }
}

@Composable
private fun ScheduleListContent(
    state: ScheduleListUiState,
    onAgentSelected: (String) -> Unit,
    onDeleteSchedule: (ScheduledMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AgentSelector(
            agents = state.agents,
            selectedAgentId = state.selectedAgentId,
            onAgentSelected = onAgentSelected,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (!state.scheduleAdminAvailable) {
            EmptyState(
                icon = LettaIcons.AccessTime,
                message = state.scheduleAdminMessage ?: stringResource(R.string.screen_schedules_unavailable),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.selectedAgentId == null) {
            EmptyState(
                icon = LettaIcons.AccessTime,
                message = stringResource(R.string.screen_conversations_dialog_no_agents),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.schedules.isEmpty()) {
            EmptyState(
                icon = LettaIcons.AccessTime,
                message = stringResource(R.string.screen_schedules_empty),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onDelete = { onDeleteSchedule(schedule) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSelector(
    agents: List<Agent>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.common_agents),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedAgent?.name ?: stringResource(R.string.screen_conversations_dialog_select_agent_title),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(LettaIcons.ExpandMore, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        expanded = false
                        onAgentSelected(agent.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: ScheduledMessage,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = schedule.message.messages.firstOrNull()?.content.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (schedule.schedule.type == "recurring") {
                            stringResource(R.string.screen_schedules_recurring_label, schedule.schedule.cronExpression.orEmpty())
                        } else {
                            stringResource(R.string.screen_schedules_one_time_label, schedule.nextScheduledTime ?: schedule.schedule.scheduledAt?.toString().orEmpty())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(LettaIcons.Delete, stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun CreateScheduleDialog(
    agents: List<Agent>,
    selectedAgentId: String?,
    onDismiss: () -> Unit,
    onCreate: (String, ScheduleCreateParams) -> Unit,
) {
    var selectedAgent by remember(agents, selectedAgentId) {
        mutableStateOf(selectedAgentId ?: agents.firstOrNull()?.id.orEmpty())
    }
    var content by remember { mutableStateOf("") }
    var isRecurring by remember { mutableStateOf(false) }
    var scheduledAt by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_schedules_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentSelector(
                    agents = agents,
                    selectedAgentId = selectedAgent,
                    onAgentSelected = { selectedAgent = it },
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.screen_schedules_message_label)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.screen_schedules_recurring_toggle), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = isRecurring, onCheckedChange = { isRecurring = it })
                }
                if (isRecurring) {
                    OutlinedTextField(
                        value = cronExpression,
                        onValueChange = { cronExpression = it },
                        label = { Text(stringResource(R.string.screen_schedules_cron_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = scheduledAt,
                        onValueChange = { scheduledAt = it },
                        label = { Text(stringResource(R.string.screen_schedules_scheduled_at_label)) },
                        placeholder = { Text(stringResource(R.string.screen_schedules_scheduled_at_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val schedule = if (isRecurring) {
                        ScheduleDefinition(type = "recurring", cronExpression = cronExpression)
                    } else {
                        ScheduleDefinition(type = "one-time", scheduledAt = scheduledAt.toDoubleOrNull())
                    }
                    onCreate(
                        selectedAgent,
                        ScheduleCreateParams(
                            messages = listOf(ScheduleMessage(content = content, role = "user")),
                            schedule = schedule,
                        )
                    )
                },
                enabled = selectedAgent.isNotBlank() && content.isNotBlank() &&
                    ((isRecurring && cronExpression.isNotBlank()) || (!isRecurring && scheduledAt.toDoubleOrNull() != null)),
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
