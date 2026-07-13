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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleLibraryItem
import com.letta.mobile.data.schedules.ScheduleTiming
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.StaggeredListItem

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
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // Native schedule admin: show the create FAB. In cron-backed mode
            // (fallback for self-hosted backends) the list is read-only for
            // now — cron create/delete is not yet wired on mobile — so
            // the FAB stays hidden to avoid a create that the native route
            // can't satisfy.
            val data = successState?.data
            if (data != null && data.scheduleAdminAvailable && !data.cronMode) {
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
    if (showCreateDialog && state != null && state.scheduleAdminAvailable && !state.cronMode) {
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

        if (state.cronMode) {
            // Cron-backed fallback (self-hosted / admin-shim backends that
            // don't serve the native schedule route). An empty cron list is
            // a real "0 schedules", NOT the unavailable wall. Parity with the
            // desktop schedules surface.
            val crons = state.cronsForSelectedAgent
            if (crons.isEmpty()) {
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
                    items(crons, key = { it.id }) { cron ->
                        CronScheduleCard(cron = cron)
                    }
                }
            }
        } else if (!state.scheduleAdminAvailable) {
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
                itemsIndexed(state.displayItems, key = { _, item -> item.schedule.id }) { index, item ->
                    StaggeredListItem(index = index) {
                        ScheduleCard(
                            item = item,
                            onDelete = { onDeleteSchedule(item.schedule) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSelector(
    agents: List<AgentSummary>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAgent = agents.firstOrNull { it.id.value == selectedAgentId }

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
                        try {
                            onAgentSelected(agent.id.value)
                        } finally {
                            expanded = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    item: ScheduleLibraryItem,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.messagePreview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.timing.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
private fun CronScheduleCard(
    cron: CronTask,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = cron.name?.takeIf { it.isNotBlank() }
                    ?: cron.prompt?.takeIf { it.isNotBlank() }
                    ?: cron.id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            cron.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.screen_schedules_recurring_label,
                    cron.cron?.takeIf { it.isNotBlank() } ?: "—",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScheduleTiming.label(): String =
    when (this) {
        is ScheduleTiming.Recurring -> stringResource(R.string.screen_schedules_recurring_label, cronExpression)
        is ScheduleTiming.OneTime -> stringResource(R.string.screen_schedules_one_time_label, displayTime)
    }

@Composable
private fun CreateScheduleDialog(
    agents: List<AgentSummary>,
    selectedAgentId: String?,
    onDismiss: () -> Unit,
    onCreate: (String, ScheduleCreateParams) -> Unit,
) {
    var selectedAgent by remember(agents, selectedAgentId) {
        mutableStateOf(selectedAgentId ?: agents.firstOrNull()?.id?.value.orEmpty())
    }
    var content by remember { mutableStateOf("") }
    var isRecurring by remember { mutableStateOf(false) }
    var scheduledAt by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_schedules_add_title),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = selectedAgent.isNotBlank() && content.isNotBlank() &&
            ((isRecurring && cronExpression.isNotBlank()) || (!isRecurring && scheduledAt.toDoubleOrNull() != null)),
        onConfirm = {
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
    ) {
        CardGroup {
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.common_agents)) }) {
                        AgentSelector(
                            agents = agents,
                            selectedAgentId = selectedAgent,
                            onAgentSelected = { selectedAgent = it },
                        )
                    }
                }
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_schedules_message_label)) }) {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            )
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_schedules_recurring_toggle)) },
                        tail = {
                            androidx.compose.material3.Switch(checked = isRecurring, onCheckedChange = { isRecurring = it })
                        }
                    )
                }
            )
            item(
                headlineContent = {
                    if (isRecurring) {
                        FormItem(label = { Text(stringResource(R.string.screen_schedules_cron_label)) }) {
                            OutlinedTextField(
                                value = cronExpression,
                                onValueChange = { cronExpression = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    } else {
                        FormItem(label = { Text(stringResource(R.string.screen_schedules_scheduled_at_label)) }) {
                            OutlinedTextField(
                                value = scheduledAt,
                                onValueChange = { scheduledAt = it },
                                placeholder = { Text(stringResource(R.string.screen_schedules_scheduled_at_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
            )
        }
    }
}
