package com.letta.mobile.ui.memory

import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.LettaChipTabRow
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaMenuItem
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.ui.components.ChevronEmphasis
import com.letta.mobile.ui.components.ChevronIndication
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaInlineError
import com.letta.mobile.ui.components.LettaPageHeader
import com.letta.mobile.ui.components.lettaCount
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** The host's presentation choices, and [canCreateBlock]: offer "New block". */
internal data class MemoryChromeOptions(val page: MemoryPageOptions, val canCreateBlock: Boolean)

/**
 * The shared page header (title, agent picker, refresh, "New block", the host's controls) with the
 * load error and the stats strip under it, above the graph. The chrome scrolls (with nothing to
 * scroll) so a pull on it reaches the page's LettaRefreshable on touch; the graph keeps its drags
 * for panning.
 */
@Composable
internal fun MemoryPageChrome(
    parity: MemoryParityControllerState,
    actions: MemoryPageActions,
    options: MemoryChromeOptions,
    modifier: Modifier = Modifier,
) {
    val picker: (@Composable () -> Unit)? = if (parity.agents.isNotEmpty()) {
        { MemoryAgentPicker(parity.agents, parity.memory.selectedAgentId, actions::selectAgent) }
    } else {
        null
    }
    LettaPageHeader(
        title = "Memory".takeIf { options.page.showTitle },
        modifier = modifier.verticalScroll(rememberScrollState()),
        primaryAction = LettaMenuItem(label = "New block", onClick = actions::beginCreate).takeIf { options.canCreateBlock },
        context = options.page.headerContext ?: picker,
        // Touch pulls the chrome down instead (MemoryPage's LettaRefreshable).
        refresh = { LettaRefreshAction(refreshing = parity.isLoading, onRefresh = actions::refresh) },
        controls = options.page.headerControls,
    ) {
        parity.errorMessage?.let { MemoryErrorBanner(it) }
        MemorySummaryStrip(parity.memory.summary)
    }
}

/** The agent whose memory the page shows: the header's context control. */
@Composable
internal fun MemoryAgentPicker(
    agents: List<MemoryParityAgentOption>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = agents.firstOrNull { it.id == selectedAgentId }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = LettaDimens.Pane.sidePanelWidth)) {
            Text(selected?.name ?: CHOOSE_AGENT, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            DisclosureChevron(
                expanded = false,
                emphasis = ChevronEmphasis.Emphasized,
                indicates = ChevronIndication.Sheet,
                contentDescription = CHOOSE_AGENT,
            )
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

private const val CHOOSE_AGENT = "Choose agent"

/**
 * Counts with grouped digits, then the context window as a meter ("801 / 1,000,000 tokens") on
 * its own line, capped at the side pane's width so it reads the same on a phone and a desktop.
 */
@Composable
private fun MemorySummaryStrip(summary: MemoryParitySummary) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        verticalAlignment = Alignment.Bottom,
    ) {
        summary.metrics.filter { it.kind != MemorySummaryMetricKind.Context }.forEach { metric ->
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.Bottom) {
                val value = metric.value.toIntOrNull()?.let(::lettaCount) ?: metric.value
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
        }
    }
    MemoryContextMeter(summary, Modifier.widthIn(max = LettaDimens.Pane.sidePanelWidth).fillMaxWidth())
}

/**  (S13): the page's load failure, as every page shows one. */
@Composable
private fun MemoryErrorBanner(message: String) {
    LettaInlineError(message = message, title = null)
}

/**
 * The Memory destination's view switch (Blocks | Files) for the header's controls row: one chip per
 * [labels] entry, tagged by [tags], the [selected] one raised.
 */
@Composable
fun MemoryViewSwitch(labels: List<String>, tags: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LettaChipTabRow {
        labels.forEachIndexed { index, label ->
            LettaChipTab(text = label, active = index == selected, onClick = { onSelect(index) }, modifier = Modifier.testTag(tags[index]))
        }
    }
}
