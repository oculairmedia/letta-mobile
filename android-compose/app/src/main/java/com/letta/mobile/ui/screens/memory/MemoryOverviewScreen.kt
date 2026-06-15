package com.letta.mobile.ui.screens.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryParityAgentOption
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.listItemHeadline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryOverviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_memory_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        MemoryOverviewContent(
            state = state,
            onRefresh = viewModel::refresh,
            onAgentSelected = viewModel::selectAgent,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun MemoryOverviewContent(
    state: MemoryParityControllerState,
    onRefresh: () -> Unit,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isLoading && state.memory.sections.isEmpty()) {
        ShimmerCard(modifier = modifier.padding(16.dp))
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MemoryOverviewHeader(
                state = state,
                onRefresh = onRefresh,
            )
        }
        state.errorMessage?.let { message ->
            item {
                MemoryErrorBanner(message)
            }
        }
        if (state.agents.isNotEmpty()) {
            item {
                MemoryAgentSelector(
                    agents = state.agents,
                    selectedAgentId = state.memory.selectedAgentId,
                    onAgentSelected = onAgentSelected,
                )
            }
        }
        item {
            MemorySummaryCard(state.memory.summary)
        }
        if (state.memory.isEmpty) {
            item {
                EmptyState(
                    icon = LettaIcons.Psychology,
                    message = stringResource(R.string.screen_memory_empty),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(
            items = state.memory.sections,
            key = { section -> section.kind.name },
        ) { section ->
            MemorySectionCard(section)
        }
    }
}

@Composable
private fun MemoryOverviewHeader(
    state: MemoryParityControllerState,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.memory.selectedAgentName?.let {
                stringResource(R.string.screen_memory_subtitle_agent, it)
            } ?: stringResource(R.string.screen_memory_subtitle_backend),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !state.isLoading,
        ) {
            Icon(LettaIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (state.isLoading) R.string.screen_memory_refreshing else R.string.screen_memory_refresh,
                ),
            )
        }
    }
}

@Composable
private fun MemoryErrorBanner(message: String) {
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
private fun MemoryAgentSelector(
    agents: List<MemoryParityAgentOption>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.screen_memory_agent_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedAgent?.name ?: agents.firstOrNull()?.name.orEmpty(),
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
}

@Composable
private fun MemorySummaryCard(summary: MemoryParitySummary) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_memory_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SummaryMetric(stringResource(R.string.screen_memory_skills_metric), summary.skillCount.toString()) }
                item { SummaryMetric(stringResource(R.string.screen_memory_blocks_metric), summary.memoryBlockCount.toString()) }
                item { SummaryMetric(stringResource(R.string.screen_memory_schedules_metric), summary.scheduleCount.toString()) }
                item { SummaryMetric(stringResource(R.string.screen_memory_channels_metric), summary.channelCount.toString()) }
                item { SummaryMetric(stringResource(R.string.screen_memory_context_metric), summary.contextUsageLabel) }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemorySectionCard(section: MemoryParitySection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = section.kind.icon(),
                    contentDescription = null,
                    tint = section.kind.tint(),
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.listItemHeadline,
                    )
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (section.items.isEmpty()) {
                Text(
                    text = section.emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.items.forEach { item ->
                        MemoryItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryItemRow(item: MemoryParityItem) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = item.icon(),
                    contentDescription = null,
                    tint = item.tint(),
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.detailText.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.metadataLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.metadataLabels.forEach { label ->
                        AssistChip(
                            onClick = {},
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

private fun MemoryParitySectionKind.icon(): ImageVector = when (this) {
    MemoryParitySectionKind.Skills -> LettaIcons.Tool
    MemoryParitySectionKind.Memory -> LettaIcons.Psychology
    MemoryParitySectionKind.Schedules -> LettaIcons.AccessTime
    MemoryParitySectionKind.Channels -> LettaIcons.Link
}

@Composable
private fun MemoryParitySectionKind.tint(): Color = when (this) {
    MemoryParitySectionKind.Skills -> MaterialTheme.colorScheme.primary
    MemoryParitySectionKind.Memory -> MaterialTheme.colorScheme.secondary
    MemoryParitySectionKind.Schedules -> MaterialTheme.colorScheme.tertiary
    MemoryParitySectionKind.Channels -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun MemoryParityItem.icon(): ImageVector = when (this) {
    is MemoryParityItem.Skill -> LettaIcons.Tool
    is MemoryParityItem.MemoryBlock -> LettaIcons.Psychology
    is MemoryParityItem.Schedule -> LettaIcons.AccessTime
    is MemoryParityItem.Channel -> LettaIcons.Link
}

@Composable
private fun MemoryParityItem.tint(): Color = when (this) {
    is MemoryParityItem.Skill -> MaterialTheme.colorScheme.primary
    is MemoryParityItem.MemoryBlock -> MaterialTheme.colorScheme.secondary
    is MemoryParityItem.Schedule -> MaterialTheme.colorScheme.tertiary
    is MemoryParityItem.Channel -> status.tint()
}

@Composable
private fun MemoryChannelStatus.tint(): Color = when (this) {
    MemoryChannelStatus.Connected -> MaterialTheme.colorScheme.tertiary
    MemoryChannelStatus.Connecting -> MaterialTheme.colorScheme.primary
    MemoryChannelStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    MemoryChannelStatus.Disconnected -> MaterialTheme.colorScheme.error
}

@PreviewLightDark
@Composable
private fun MemoryOverviewContentPreview() {
    LettaTheme(dynamicColor = false) {
        Surface {
            MemoryOverviewContent(
                state = previewMemoryState(),
                onRefresh = {},
                onAgentSelected = {},
            )
        }
    }
}

private fun previewMemoryState(): MemoryParityControllerState =
    MemoryParityControllerState(
        memory = MemoryParityState(
            selectedAgentId = "agent-1",
            selectedAgentName = "Tutor",
            sections = listOf(
                MemoryParitySection(
                    kind = MemoryParitySectionKind.Skills,
                    title = "Skills",
                    subtitle = "Tools and callable skills attached to the active agent.",
                    emptyMessage = "No skills attached.",
                    items = listOf(
                        MemoryParityItem.Skill(
                            id = "tool-1",
                            title = "web_search",
                            subtitle = "Search the web for current sources.",
                            detailText = "Uses https://example.com and @research for retrieval.",
                            metadataLabels = listOf("tool", "search"),
                            links = emptyList(),
                            type = "tool",
                            tags = listOf("search"),
                        ),
                    ),
                ),
                MemoryParitySection(
                    kind = MemoryParitySectionKind.Memory,
                    title = "Memory",
                    subtitle = "Core memory blocks available to the selected agent.",
                    emptyMessage = "No memory blocks attached.",
                    items = listOf(
                        MemoryParityItem.MemoryBlock(
                            id = "block-1",
                            title = "persona",
                            subtitle = "Limit 5000 chars",
                            detailText = "Prefers concise answers and keeps desktop state separate per agent.",
                            metadataLabels = listOf("Limit 5000", "Read-only"),
                            links = emptyList(),
                            preview = "Prefers concise answers and keeps desktop state separate per agent.",
                            limit = 5000,
                            readOnly = true,
                        ),
                    ),
                ),
                MemoryParitySection(
                    kind = MemoryParitySectionKind.Schedules,
                    title = "Memory schedules",
                    subtitle = "Scheduled messages and recurring memory maintenance.",
                    emptyMessage = "No memory schedules configured.",
                    items = listOf(
                        MemoryParityItem.Schedule(
                            id = "schedule-1",
                            title = "Weekly memory summary",
                            subtitle = "Recurring: 0 9 * * MON",
                            detailText = "Recurring: 0 9 * * MON",
                            metadataLabels = listOf("recurring", "Monday 9:00"),
                            links = emptyList(),
                            scheduleType = "recurring",
                            nextRunLabel = "Monday 9:00",
                        ),
                    ),
                ),
                MemoryParitySection(
                    kind = MemoryParitySectionKind.Channels,
                    title = "Channels",
                    subtitle = "Live channel and backend delivery status.",
                    emptyMessage = "No channels available.",
                    items = listOf(
                        MemoryParityItem.Channel(
                            id = "backend-1",
                            title = "Local runtime",
                            subtitle = "Connected via websocket",
                            detailText = "Connected via websocket",
                            metadataLabels = listOf("Connected"),
                            links = emptyList(),
                            status = MemoryChannelStatus.Connected,
                        ),
                    ),
                ),
            ),
            summary = MemoryParitySummary(
                skillCount = 1,
                memoryBlockCount = 1,
                scheduleCount = 1,
                channelCount = 1,
                totalMemoryTokens = 1204,
                contextWindowUsed = 1204,
                contextWindowLimit = 8192,
            ),
        ),
        agents = listOf(
            MemoryParityAgentOption(id = "agent-1", name = "Tutor"),
            MemoryParityAgentOption(id = "agent-2", name = "Researcher"),
        ),
    )
