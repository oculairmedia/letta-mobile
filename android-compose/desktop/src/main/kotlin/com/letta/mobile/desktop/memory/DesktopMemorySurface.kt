package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arjunjadeja.texty.DisplayStyle
import com.arjunjadeja.texty.Texty
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.edge
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.model.nodes
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.EdgeLabelStyle
import com.dk.kuiver.ui.LabelPlacement
import com.dk.kuiver.ui.StyledEdgeContent
import com.letta.mobile.data.memory.MemoryAccentRole
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.data.memory.MemoryTextLink
import com.letta.mobile.data.memory.accentRole
import com.letta.mobile.data.memory.validForText
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
        val errorMessage = state.errorMessage
        if (errorMessage != null) {
            item {
                MemoryErrorBanner(errorMessage)
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
        item {
            MemoryGraphPanel(state.memory.graph)
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
            Texty(
                text = "Memory",
                displayStyle = DisplayStyle.Basic(),
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = state.memory.scopeSubtitle,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        shape = MaterialTheme.shapes.medium,
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
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = metric.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
        )
    }
}

@Composable
private fun MemoryGraphPanel(graph: MemoryParityGraph) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) {
        if (graph.isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No memory graph available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Surface
        }

        val nodesById = remember(graph.nodes) { graph.nodes.associateBy { it.id } }
        val edgesById = remember(graph.edges) { graph.edges.associateBy { it.id } }
        val kuiver = remember(graph) {
            buildKuiver {
                nodes(graph.nodes.map { it.id })
                graph.edges.forEach { edge ->
                    edge(edge.fromId, edge.toId)
                }
            }
        }
        val layoutConfig = remember {
            LayoutConfig.Hierarchical(
                direction = LayoutDirection.HORIZONTAL,
                levelSpacing = 210f,
                nodeSpacing = 92f,
            )
        }
        val viewerState = rememberKuiverViewerState(
            initialKuiver = kuiver,
            layoutConfig = layoutConfig,
        )
        LaunchedEffect(kuiver) {
            viewerState.updateKuiver(kuiver)
        }

        Box(Modifier.fillMaxSize()) {
            KuiverViewer(
                state = viewerState,
                config = KuiverViewerConfig(
                    fitToContent = true,
                    contentPadding = 0.72f,
                    animateInitialPlacement = false,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.36f)),
                nodeContent = { node ->
                    MemoryGraphNodeChip(nodesById[node.id])
                },
                edgeContent = { edge, from, to ->
                    val commonEdge = edgesById["${edge.fromId}->${edge.toId}"]
                    StyledEdgeContent(
                        edge = edge,
                        from = from,
                        to = to,
                        baseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.78f),
                        backEdgeColor = MaterialTheme.colorScheme.error,
                        strokeWidth = 2.2f,
                        label = commonEdge?.label,
                        labelPlacement = LabelPlacement.CENTER,
                        labelStyle = EdgeLabelStyle(
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                        ),
                    )
                },
            )

            GraphLegend(
                summaryLabel = graph.summaryLabel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun GraphLegend(
    summaryLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Texty(
                text = "Memory graph",
                displayStyle = DisplayStyle.Basic(),
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = summaryLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
private fun MemoryGraphNodeChip(node: MemoryGraphNode?) {
    val resolvedNode = node ?: return
    val accentColor = resolvedNode.kind.accentRole(resolvedNode.status).color()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.46f)),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .border(
                    width = 3.dp,
                    color = accentColor.copy(alpha = 0.18f),
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(9.dp)
                    .background(accentColor, MaterialTheme.shapes.small),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.width(172.dp),
            ) {
                Text(
                    text = resolvedNode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = resolvedNode.subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MemorySectionCard(section: MemoryParitySection) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        shape = MaterialTheme.shapes.medium,
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

private fun MemoryParitySectionKind.icon(): ImageVector = when (this) {
    MemoryParitySectionKind.Skills -> Icons.Outlined.Build
    MemoryParitySectionKind.Memory -> Icons.Outlined.Memory
    MemoryParitySectionKind.Schedules -> Icons.Outlined.Schedule
    MemoryParitySectionKind.Channels -> Icons.Outlined.Hub
}

@Composable
private fun MemoryParityItem.accentColor(): Color = accentRole.color()

@Composable
private fun MemoryAccentRole.color(): Color = when (this) {
    MemoryAccentRole.Primary -> MaterialTheme.colorScheme.primary
    MemoryAccentRole.Secondary -> MaterialTheme.colorScheme.secondary
    MemoryAccentRole.Tertiary -> MaterialTheme.colorScheme.tertiary
    MemoryAccentRole.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    MemoryAccentRole.Error -> MaterialTheme.colorScheme.error
}
