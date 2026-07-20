package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.components.DesktopRefreshAction
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.data.memory.MemoryTextLink
import com.letta.mobile.data.memory.validForText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import sh.calvin.autolinktext.SimpleTextMatchResult
import sh.calvin.autolinktext.TextMatcher
import sh.calvin.autolinktext.TextRule
import sh.calvin.autolinktext.rememberAutoLinkText

@Composable
fun DesktopMemorySurface(
    state: DesktopMemorySurfaceState,
    onRefresh: () -> Unit,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    blockApi: DesktopBlockApi? = null,
    onBlockChanged: () -> Unit = {},
) {
    var editorTarget by remember { mutableStateOf<BlockEditorTarget?>(null) }
    val agentId = state.memory.selectedAgentId
    val chrome = MemorySurfaceChrome(state, onRefresh, onAgentSelected, blockApi)

    Row(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        MemoryMainColumn(
            chrome = chrome,
            agentId = agentId,
            onEditorTargetChange = { editorTarget = it },
        )
        MemoryBlockEditorSlot(
            MemoryBlockEditorSlotParams(
                target = editorTarget,
                agentId = agentId,
                blockApi = blockApi,
                onDismiss = { editorTarget = null },
                onChanged = {
                    editorTarget = null
                    onBlockChanged()
                },
            ),
        )
    }
}

@Composable
internal fun MemoryHeader(
    state: DesktopMemorySurfaceState,
    onRefresh: () -> Unit,
    onNewBlock: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Memory",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        onNewBlock?.let { newBlock ->
            DesktopDefaultButton(onClick = newBlock) {
                DesktopButtonContent(text = "New block", icon = Icons.Outlined.Add)
            }
        }
        DesktopRefreshAction(onRefresh = onRefresh, enabled = !state.isLoading)
    }
}

@Composable
internal fun AgentSelector(
    agents: List<DesktopMemoryAgentOption>,
    selectedAgentId: String?,
    onAgentSelected: (String) -> Unit,
) {
    // Slim inline agent strip (no card) — keeps the top compact like the
    // Schedules header.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Text(
                text = "Agent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.customColors.onSurfaceMutedColor,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        items(
            items = agents,
            key = { it.id },
        ) { agent ->
            DesktopChipTab(
                text = agent.name,
                active = agent.id == selectedAgentId,
                onClick = { onAgentSelected(agent.id) },
            )
        }
    }
}

@Composable
internal fun MemorySummaryCard(summary: MemoryParitySummary) {
    // Neutral stats strip — these are reference counts, not a status to flag,
    // so no colour highlight.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            summary.metrics.forEach { metric ->
                SummaryMetric(
                    metric = metric,
                    modifier = Modifier.weight(if (metric.kind == MemorySummaryMetricKind.Context) 1.2f else 1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    metric: MemorySummaryMetric,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        Text(
            text = metric.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

@Composable
private fun MemoryItemRow(
    item: MemoryParityItem,
    onClick: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
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
            LinkedDetailText(
                text = item.detailText,
                links = item.links,
            )
            MetadataRow(item)
        }
    }
}

@Composable
private fun LinkedDetailText(
    text: String,
    links: List<MemoryTextLink>,
) {
    val validLinks = remember(text, links) {
        links.validForText(text)
    }
    val annotatedText = if (validLinks.isEmpty()) {
        AnnotatedString(text)
    } else {
        val linkStyle = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        )
        val linkRule = remember(validLinks, linkStyle) {
            TextRule.Styleable(
                textMatcher = TextMatcher.FunctionMatcher<Any?> {
                    validLinks.map { link ->
                        SimpleTextMatchResult(link.start, link.end, link)
                    }
                },
                styles = linkStyle,
            )
        }
        AnnotatedString.rememberAutoLinkText(
            text = text,
            textRules = listOf(linkRule),
        )
    }

    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetadataRow(item: MemoryParityItem) {
    if (item.metadataLabels.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.metadataLabels.forEach { label ->
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

internal sealed interface BlockEditorTarget {
    data object New : BlockEditorTarget
    data class Existing(val label: String, val blockId: String?) : BlockEditorTarget
}

/** Right-side panel for viewing/editing a core-memory block (CRUD). */
