package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleLibraryItem
import com.letta.mobile.data.schedules.ScheduleTiming
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopRadioChip
import com.letta.mobile.desktop.DesktopTextArea
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

@Composable
fun DesktopScheduleLibrarySurface(
    state: DesktopScheduleLibraryState,
    onRefresh: () -> Unit,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    crons: List<CronTask> = emptyList(),
    focusedAgentId: String? = null,
    onDeleteCron: (String) -> Unit = {},
    canCreate: Boolean = false,
    onCreateCron: (name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit = { _, _, _, _, _ -> },
) {
    var showCreate by remember { mutableStateOf(false) }
    // Schedules are scoped to the agent in focus by default; the "All" chip
    // (filterAgentId == null) widens the view to every agent's schedules.
    var filterAgentId by remember(focusedAgentId) { mutableStateOf(focusedAgentId) }
    val filteredCrons = remember(crons, filterAgentId) {
        if (filterAgentId == null) crons else crons.filter { it.agentId == filterAgentId }
    }
    val cronAgentIds = remember(crons, focusedAgentId) {
        (crons.mapNotNull { it.agentId?.takeIf(String::isNotBlank) } + listOfNotNull(focusedAgentId))
            .distinct()
    }
    val agentNames = remember(state.agents) { state.agents.associate { it.id.value to it.name } }
    Box(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SchedulesHeader(
                    state = state,
                    cronCount = if (crons.isNotEmpty()) filteredCrons.size else 0,
                    canCreate = canCreate,
                    onRefresh = onRefresh,
                    onNewSchedule = { showCreate = true },
                )
            }
        // Real schedules on this backend are exposed as cron tasks (/v1/crons).
        if (crons.isNotEmpty()) {
            item {
                CronAgentFilters(
                    agentIds = cronAgentIds,
                    agentNames = agentNames,
                    selectedAgentId = filterAgentId,
                    onSelect = { filterAgentId = it },
                )
            }
            if (filteredCrons.isEmpty()) {
                item { SchedulesInfoCard("No schedules for this agent yet. Use “New schedule” to add one.") }
            } else {
                items(items = filteredCrons, key = { it.id }) { cron ->
                    CronRow(cron = cron, onDelete = { onDeleteCron(cron.id) })
                }
            }
            return@LazyColumn
        }
        state.errorMessage?.let { message ->
            item {
                SchedulesErrorBanner(message)
            }
        }
        if (state.agents.isNotEmpty()) {
            item {
                AgentFilters(
                    state = state,
                    onAgentSelected = onAgentSelected,
                )
            }
        }
        if (state.isLoading && state.schedules.isEmpty()) {
            item {
                SchedulesInfoCard("Loading schedules from the active backend.")
            }
        } else if (!state.scheduleAdminAvailable || state.selectedAgentId == null || state.schedules.isEmpty()) {
            item {
                SchedulesInfoCard(state.emptyMessage)
            }
        } else {
            items(
                items = state.displayItems,
                key = { item -> item.schedule.id },
            ) { item ->
                ScheduleRow(item)
            }
        }
        }
        if (showCreate) {
            CronCreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { name, prompt, cron, recurring, tz ->
                    showCreate = false
                    onCreateCron(name, prompt, cron, recurring, tz)
                },
            )
        }
    }
}

@Composable
private fun SchedulesHeader(
    state: DesktopScheduleLibraryState,
    cronCount: Int,
    canCreate: Boolean,
    onRefresh: () -> Unit,
    onNewSchedule: () -> Unit,
) {
    val count = if (cronCount > 0) cronCount else state.schedules.size
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Schedules",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$count scheduled task${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canCreate) {
            DesktopDefaultButton(onClick = onNewSchedule) {
                DesktopButtonContent(text = "New schedule", icon = Icons.Outlined.Add)
            }
        }
        DesktopOutlinedButton(
            onClick = onRefresh,
            enabled = !state.isLoading,
        ) {
            DesktopButtonContent(
                text = if (state.isLoading) "Refreshing" else "Refresh",
                icon = Icons.Outlined.Refresh,
            )
        }
    }
}

/** Modal for creating a recurring cron schedule. */
@Composable
private fun CronCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var cron by remember { mutableStateOf(TextFieldValue("0 9 * * *")) }
    var timezone by remember { mutableStateOf(TextFieldValue("America/Toronto")) }
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(480.dp).clickable(enabled = false) {},
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JewelTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                Text("Cron expression", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JewelTextField(value = cron, onValueChange = { cron = it }, modifier = Modifier.fillMaxWidth())
                Text("Timezone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JewelTextField(value = timezone, onValueChange = { timezone = it }, modifier = Modifier.fillMaxWidth())
                Text("Prompt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DesktopTextArea(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = "What should the agent do on each run?",
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(onClick = {
                        when {
                            name.text.isBlank() -> error = "Name is required"
                            cron.text.isBlank() -> error = "Cron expression is required"
                            prompt.isBlank() -> error = "Prompt is required"
                            else -> onCreate(name.text.trim(), prompt.trim(), cron.text.trim(), true, timezone.text.trim().ifBlank { "UTC" })
                        }
                    }) { DesktopButtonContent("Create") }
                }
            }
        }
    }
}

@Composable
private fun CronRow(
    cron: CronTask,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = cron.name?.ifBlank { null } ?: cron.id,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                cron.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataPill(humanizeCron(cron.cron), MaterialTheme.colorScheme.tertiary)
                    if (cron.recurring) MetadataPill("recurring", MaterialTheme.colorScheme.secondary)
                    cron.timezone?.takeIf { it.isNotBlank() }?.let {
                        MetadataPill(it, MaterialTheme.colorScheme.primary)
                    }
                }
            }
            DesktopDefaultButton(onClick = onDelete) {
                DesktopButtonContent(text = "Delete")
            }
        }
    }
}

/** Best-effort human label for a 5-field cron expression. */
private fun humanizeCron(cron: String?): String {
    if (cron.isNullOrBlank()) return "no schedule"
    val parts = cron.trim().split(Regex("\\s+"))
    if (parts.size < 5) return cron
    val min = parts[0]; val hr = parts[1]
    Regex("""\*/(\d+)""").matchEntire(min)?.let { return "Every ${it.groupValues[1]} min" }
    Regex("""\*/(\d+)""").matchEntire(hr)?.let { return "Every ${it.groupValues[1]}h" }
    val minN = min.toIntOrNull(); val hrN = hr.toIntOrNull()
    return when {
        minN != null && hr == "*" -> "Hourly at :${min.padStart(2, '0')}"
        minN != null && hrN != null -> "Daily at ${hr.padStart(2, '0')}:${min.padStart(2, '0')}"
        else -> cron
    }
}

/**
 * Filter chips for the cron list: an "All" chip plus one per agent that owns a
 * schedule (and the focused agent). Selecting an agent scopes the list to it;
 * "All" shows every agent's schedules.
 */
@Composable
private fun CronAgentFilters(
    agentIds: List<String>,
    agentNames: Map<String, String>,
    selectedAgentId: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DesktopRadioChip(
                selected = selectedAgentId == null,
                onClick = { onSelect(null) },
            ) {
                DesktopControlText(text = "All")
            }
        }
        items(items = agentIds, key = { it }) { id ->
            DesktopRadioChip(
                selected = id == selectedAgentId,
                onClick = { onSelect(id) },
            ) {
                DesktopControlText(
                    text = agentNames[id] ?: "Agent ${id.takeLast(4)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentFilters(
    state: DesktopScheduleLibraryState,
    onAgentSelected: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(
            items = state.agents,
            key = { agent -> agent.id.value },
        ) { agent ->
            DesktopRadioChip(
                selected = agent.id.value == state.selectedAgentId,
                onClick = { onAgentSelected(agent.id.value) },
            ) {
                DesktopControlText(
                    text = agent.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScheduleRow(item: ScheduleLibraryItem) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = item.messagePreview.ifBlank { "(empty message)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.timing.desktopLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataPill(item.schedule.id, MaterialTheme.colorScheme.secondary)
                    MetadataPill(item.schedule.agentId, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun ScheduleTiming.desktopLabel(): String =
    when (this) {
        is ScheduleTiming.Recurring -> "Recurring: ${cronExpression.ifBlank { "no cron expression" }}"
        is ScheduleTiming.OneTime -> "One-time: ${displayTime.ifBlank { "unscheduled" }}"
    }

@Composable
private fun SchedulesInfoCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SchedulesErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun MetadataPill(
    text: String,
    color: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
