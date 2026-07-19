package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.edge
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.nodes
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.StyledEdgeContent
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.components.DesktopRefreshAction
import com.letta.mobile.data.memory.MemoryAccentRole
import com.letta.mobile.data.memory.MemoryCategories
import com.letta.mobile.data.memory.MemoryCategory
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.data.memory.MemoryTextLink
import com.letta.mobile.data.memory.accentRole
import com.letta.mobile.data.memory.validForText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import androidx.compose.ui.text.input.TextFieldValue
import sh.calvin.autolinktext.SimpleTextMatchResult
import sh.calvin.autolinktext.TextMatcher
import sh.calvin.autolinktext.TextRule
import sh.calvin.autolinktext.rememberAutoLinkText

@Composable
internal fun MemoryGraphPanel(
    graph: MemoryParityGraph,
    onBlockNodeClick: (MemoryGraphNode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
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

        // Entity-type filter (Zep "Entity Types"): toggle which node kinds show.
        val kindsPresent = remember(graph.nodes) {
            MemoryGraphNodeKind.entries.filter { kind -> graph.nodes.any { it.kind == kind } }
        }
        var enabledKinds by remember(graph) { mutableStateOf(kindsPresent.toSet()) }
        val visibleNodes = remember(graph.nodes, enabledKinds) { graph.nodes.filter { it.kind in enabledKinds } }
        val visibleIds = remember(visibleNodes) { visibleNodes.map { it.id }.toSet() }
        val visibleEdges = remember(graph.edges, visibleIds) {
            graph.edges.filter { it.fromId in visibleIds && it.toId in visibleIds }
        }

        val nodesById = remember(visibleNodes) { visibleNodes.associateBy { it.id } }
        // Node degree drives circle size + which nodes get labels (hubs), so the
        // graph reads like Zep's: a few large labeled hubs, many small leaves.
        val degreeById = remember(visibleEdges) {
            val degrees = HashMap<String, Int>()
            visibleEdges.forEach { edge ->
                degrees[edge.fromId] = (degrees[edge.fromId] ?: 0) + 1
                degrees[edge.toId] = (degrees[edge.toId] ?: 0) + 1
            }
            degrees
        }
        val kuiver = remember(visibleNodes, visibleEdges) {
            buildKuiver {
                nodes(visibleNodes.map { it.id })
                visibleEdges.forEach { edge ->
                    edge(edge.fromId, edge.toId)
                }
            }
        }
        // Organic force-directed layout (like Zep / Cosmograph), not a tree:
        // nodes repel, edges pull, so hubs settle in the middle.
        val layoutConfig = remember {
            // Tuned softer than the default: lower repulsion + higher attraction
            // so clusters stay compact instead of flinging apart.
            LayoutConfig.ForceDirected(
                iterations = 420,
                repulsionStrength = 3200f,
                attractionStrength = 0.14f,
                damping = 0.82f,
                width = 1000f,
                height = 680f,
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
                modifier = Modifier.fillMaxSize(),
                nodeContent = { node ->
                    val resolved = nodesById[node.id]
                    val clickable = resolved?.kind == MemoryGraphNodeKind.Memory
                    MemoryGraphNodeDot(
                        node = resolved,
                        degree = degreeById[node.id] ?: 0,
                        onClick = if (clickable && resolved != null) {
                            { onBlockNodeClick(resolved) }
                        } else {
                            null
                        },
                    )
                },
                edgeContent = { edge, from, to ->
                    // Thin, unlabeled links — Zep keeps the graph clean and lets
                    // the node clusters carry the meaning.
                    StyledEdgeContent(
                        edge = edge,
                        from = from,
                        to = to,
                        baseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                        backEdgeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                        strokeWidth = 1.2f,
                        // No arrowheads — Zep draws plain links.
                        arrowDrawer = { _, _, _ -> },
                        label = null,
                    )
                },
            )

            EntityTypeFilterBar(
                kinds = kindsPresent,
                enabled = enabledKinds,
                summaryLabel = graph.summaryLabel,
                onToggle = { kind ->
                    enabledKinds = if (kind in enabledKinds && enabledKinds.size > 1) {
                        enabledKinds - kind
                    } else {
                        enabledKinds + kind
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            )
        }
    }
}

/**
 * Zep-style "Entity Types" filter bar: a toggle chip per node kind present in
 * the graph (color dot + label). Tapping a chip hides/shows that kind; the last
 * enabled kind can't be turned off (so the graph is never blank).
 */
@Composable
internal fun EntityTypeFilterBar(
    kinds: List<MemoryGraphNodeKind>,
    enabled: Set<MemoryGraphNodeKind>,
    summaryLabel: String,
    onToggle: (MemoryGraphNodeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Entity Types", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summaryLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { kind ->
                    val on = kind in enabled
                    val color = kind.accentRole(null).color()
                    Row(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                            .clickable { onToggle(kind) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (on) 1f else 0.35f)))
                        Text(
                            memoryNodeKindLabel(kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A graph node rendered the way Zep / Cosmograph do: a filled circle whose
 * radius grows with the node's [degree] (so hubs are visibly bigger), colored
 * by memory category / node kind, with a label shown only for hub nodes so the
 * canvas stays readable.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MemoryGraphNodeDot(
    node: MemoryGraphNode?,
    degree: Int,
    onClick: (() -> Unit)? = null,
) {
    val resolvedNode = node ?: return
    val accentColor = if (resolvedNode.kind == MemoryGraphNodeKind.Memory) {
        MemoryCategories.categorize(resolvedNode.title).categoryColor()
    } else {
        resolvedNode.kind.accentRole(resolvedNode.status).color()
    }
    // Hubs (well-connected nodes) + Agent/Backend read as primary, so they get
    // a stronger label; every other node still gets a label underneath, just
    // lighter — so the whole graph is legible at a glance.
    val isHub = degree >= 3 ||
        resolvedNode.kind == MemoryGraphNodeKind.Agent ||
        resolvedNode.kind == MemoryGraphNodeKind.Backend
    val diameter = (16 + degree * 4).coerceIn(14, 40).dp

    TooltipArea(
        tooltip = { MemoryNodeTooltip(resolvedNode, degree, accentColor) },
        delayMillis = 250,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(12.dp, 12.dp)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ) {
            Box(
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(accentColor)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
            Text(
                text = resolvedNode.title,
                style = if (isHub) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                fontWeight = if (isHub) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHub) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}

/** Hover card for a graph node — name, type, connection count, char count,
 *  and the summary (Zep shows the entity's details on hover/click). */
@Composable
internal fun MemoryNodeTooltip(node: MemoryGraphNode, degree: Int, accentColor: Color) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accentColor))
                Text(node.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TooltipStat("Type", memoryNodeKindLabel(node.kind))
                TooltipStat("Links", degree.toString())
                TooltipStat("Chars", node.subtitle.length.toString())
            }
            if (node.subtitle.isNotBlank()) {
                Text(
                    node.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TooltipStat(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun memoryNodeKindLabel(kind: MemoryGraphNodeKind): String = when (kind) {
    MemoryGraphNodeKind.Agent -> "Agent"
    MemoryGraphNodeKind.Backend -> "Backend"
    MemoryGraphNodeKind.Skill -> "Skill"
    MemoryGraphNodeKind.Memory -> "Memory"
    MemoryGraphNodeKind.Schedule -> "Schedule"
    MemoryGraphNodeKind.Channel -> "Channel"
}
