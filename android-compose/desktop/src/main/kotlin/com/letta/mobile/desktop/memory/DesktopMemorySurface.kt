package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySummary

@Composable
fun DesktopMemorySurface(
    state: DesktopMemorySurfaceState,
    onRefresh: () -> Unit,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            MemoryHeader(
                state = state,
                onRefresh = onRefresh,
            )
        }
        if (state.errorMessage != null) {
            item {
                MemoryErrorBanner(state.errorMessage)
            }
        }
        if (state.agents.isNotEmpty()) {
            item {
                AgentSelector(
                    agents = state.agents,
                    selectedAgentId = state.memory.selectedAgentId,
                    onAgentSelected = onAgentSelected,
                )
            }
        }
        item {
            MemorySummaryCard(state.memory.summary)
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
private fun MemoryHeader(
    state: DesktopMemorySurfaceState,
    onRefresh: () -> Unit,
) {
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
                text = "Memory",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.memory.selectedAgentName
                    ?.let { "Skills, memory, schedules, and channels for $it." }
                    ?: "Skills, memory, schedules, and channels for the active backend.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onRefresh,
            enabled = !state.isLoading,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isLoading) "Refreshing" else "Refresh")
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
private fun AgentSelector(
    agents: List<DesktopMemoryAgentOption>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Agent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = agents,
                    key = { it.id },
                ) { agent ->
                    FilterChip(
                        selected = agent.id == selectedAgentId,
                        onClick = { onAgentSelected(agent.id) },
                        label = { Text(agent.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemorySummaryCard(summary: MemoryParitySummary) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryMetric("Skills", summary.skillCount.toString(), Modifier.weight(1f))
            SummaryMetric("Blocks", summary.memoryBlockCount.toString(), Modifier.weight(1f))
            SummaryMetric("Schedules", summary.scheduleCount.toString(), Modifier.weight(1f))
            SummaryMetric("Channels", summary.channelCount.toString(), Modifier.weight(1f))
            SummaryMetric("Context", summary.contextUsageLabel(), Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
        )
    }
}

@Composable
private fun MemorySectionCard(section: MemoryParitySection) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = section.kind.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
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
                section.items.forEach { item ->
                    MemoryItemRow(item)
                }
            }
        }
    }
}

@Composable
private fun MemoryItemRow(item: MemoryParityItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .background(item.accentColor(), MaterialTheme.shapes.small),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.detailText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetadataRow(item)
        }
    }
}

@Composable
private fun MetadataRow(item: MemoryParityItem) {
    val labels = item.metadataLabels()
    if (labels.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            MetadataPill(label, item.accentColor())
        }
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

private fun MemoryParitySectionKind.icon(): ImageVector = when (this) {
    MemoryParitySectionKind.Skills -> Icons.Outlined.Build
    MemoryParitySectionKind.Memory -> Icons.Outlined.Memory
    MemoryParitySectionKind.Schedules -> Icons.Outlined.Schedule
    MemoryParitySectionKind.Channels -> Icons.Outlined.Hub
}

@Composable
private fun MemoryParityItem.accentColor(): Color = when (this) {
    is MemoryParityItem.Skill -> MaterialTheme.colorScheme.primary
    is MemoryParityItem.MemoryBlock -> MaterialTheme.colorScheme.secondary
    is MemoryParityItem.Schedule -> MaterialTheme.colorScheme.tertiary
    is MemoryParityItem.Channel -> when (status) {
        MemoryChannelStatus.Connected -> MaterialTheme.colorScheme.primary
        MemoryChannelStatus.Connecting -> MaterialTheme.colorScheme.tertiary
        MemoryChannelStatus.Idle -> MaterialTheme.colorScheme.secondary
        MemoryChannelStatus.Disconnected -> MaterialTheme.colorScheme.error
    }
}

private fun MemoryParityItem.detailText(): String = when (this) {
    is MemoryParityItem.MemoryBlock -> preview.ifBlank { subtitle }
    else -> subtitle
}

private fun MemoryParityItem.metadataLabels(): List<String> = when (this) {
    is MemoryParityItem.Skill -> listOf(type) + tags.take(2)
    is MemoryParityItem.MemoryBlock -> listOfNotNull(
        limit?.let { "Limit $it" },
        "Read-only".takeIf { readOnly },
    )
    is MemoryParityItem.Schedule -> listOf(scheduleType, nextRunLabel)
    is MemoryParityItem.Channel -> listOf(status.name)
}

private fun MemoryParitySummary.contextUsageLabel(): String {
    val used = contextWindowUsed
    val limit = contextWindowLimit
    return when {
        used != null && limit != null -> "$used / $limit"
        used != null -> used.toString()
        totalMemoryTokens > 0 -> "$totalMemoryTokens tokens"
        else -> "Not loaded"
    }
}
