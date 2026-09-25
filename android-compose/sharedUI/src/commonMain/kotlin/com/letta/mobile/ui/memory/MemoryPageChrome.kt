package com.letta.mobile.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.memory.MemoryParityAgentOption
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** [showTitle]: the host does not title the screen; [canCreateBlock]: offer "New block". */
internal data class MemoryChromeOptions(val showTitle: Boolean, val canCreateBlock: Boolean)

/** Title row, agent picker and stats strip above the graph. */
@Composable
internal fun MemoryPageChrome(
    parity: MemoryParityControllerState,
    actions: MemoryPageActions,
    options: MemoryChromeOptions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        MemoryHeaderRow(parity, actions, options)
        parity.errorMessage?.let { MemoryErrorBanner(it) }
        MemorySummaryStrip(parity.memory.summary.metrics)
    }
}

@Composable
private fun MemoryHeaderRow(
    parity: MemoryParityControllerState,
    actions: MemoryPageActions,
    options: MemoryChromeOptions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        if (options.showTitle) {
            Text("Memory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) {
            if (parity.agents.isNotEmpty()) {
                MemoryAgentPicker(parity.agents, parity.memory.selectedAgentId, actions::selectAgent)
            }
        }
        if (options.canCreateBlock) {
            IconButton(onClick = actions::beginCreate) {
                Icon(LettaIcons.Add, contentDescription = "New block")
            }
        }
        IconButton(onClick = actions::refresh, enabled = !parity.isLoading) {
            Icon(LettaIcons.Refresh, contentDescription = if (parity.isLoading) "Refreshing" else "Refresh")
        }
    }
}

@Composable
private fun MemoryAgentPicker(
    agents: List<MemoryParityAgentOption>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = agents.firstOrNull { it.id == selectedAgentId } ?: agents.first()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = LettaDimens.Pane.sidePanelWidth)) {
            Text(selected.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(LettaIcons.ExpandMore, contentDescription = "Choose agent", modifier = Modifier.size(LettaDimens.Control.icon))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
private fun MemorySummaryStrip(metrics: List<MemorySummaryMetric>) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        verticalAlignment = Alignment.Bottom,
    ) {
        metrics.forEach { metric ->
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.Bottom) {
                Text(metric.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
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
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(LettaDimens.Space.md))
    }
}
