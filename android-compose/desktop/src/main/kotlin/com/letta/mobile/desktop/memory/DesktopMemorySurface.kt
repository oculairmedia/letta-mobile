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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import com.letta.mobile.data.memory.MemoryCategories
import com.letta.mobile.data.memory.MemoryCategory
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.data.memory.MemoryTextLink
import com.letta.mobile.data.memory.accentRole
import com.letta.mobile.data.memory.validForText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopRadioChip
import com.letta.mobile.desktop.DesktopTextArea
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import androidx.compose.ui.text.input.TextFieldValue
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

    Row(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
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
                    DesktopInlineError(
                        message = errorMessage,
                        onRetry = onRefresh,
                        retrying = state.isLoading,
                    )
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
                MemoryGraphPanel(
                    graph = state.memory.graph,
                    onBlockNodeClick = { node ->
                        if (agentId != null) {
                            editorTarget = BlockEditorTarget.Existing(node.title, node.sourceItemId)
                        }
                    },
                )
            }
            items(
                items = state.memory.sections,
                key = { section -> section.kind.name },
            ) { section ->
                MemorySectionCard(
                    section = section,
                    canEdit = agentId != null && blockApi != null,
                    onBlockClick = { block -> editorTarget = BlockEditorTarget.Existing(block.title, block.id) },
                    onNewBlock = { editorTarget = BlockEditorTarget.New },
                )
            }
        }

        val target = editorTarget
        if (target != null && agentId != null && blockApi != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            BlockEditorPanel(
                target = target,
                agentId = agentId,
                blockApi = blockApi,
                onDismiss = { editorTarget = null },
                onChanged = {
                    editorTarget = null
                    onBlockChanged()
                },
            )
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
        DesktopDefaultButton(
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
                    DesktopRadioChip(
                        selected = agent.id == selectedAgentId,
                        onClick = { onAgentSelected(agent.id) },
                    ) {
                        DesktopControlText(agent.name)
                    }
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
private fun MemoryGraphPanel(
    graph: MemoryParityGraph,
    onBlockNodeClick: (MemoryGraphNode) -> Unit = {},
) {
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
                    val resolved = nodesById[node.id]
                    val clickable = resolved?.kind == MemoryGraphNodeKind.Memory
                    MemoryGraphNodeChip(
                        node = resolved,
                        onClick = if (clickable && resolved != null) {
                            { onBlockNodeClick(resolved) }
                        } else {
                            null
                        },
                    )
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
private fun MemoryGraphNodeChip(
    node: MemoryGraphNode?,
    onClick: (() -> Unit)? = null,
) {
    val resolvedNode = node ?: return
    // Memory blocks are color-coded by category (Phase 6); other node kinds
    // keep their role-based accent.
    val accentColor = if (resolvedNode.kind == MemoryGraphNodeKind.Memory) {
        MemoryCategories.categorize(resolvedNode.title).categoryColor()
    } else {
        resolvedNode.kind.accentRole(resolvedNode.status).color()
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.46f)),
        shadowElevation = 2.dp,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
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
private fun MemorySectionCard(
    section: MemoryParitySection,
    canEdit: Boolean = false,
    onBlockClick: (MemoryParityItem.MemoryBlock) -> Unit = {},
    onNewBlock: () -> Unit = {},
) {
    val isMemory = section.kind == MemoryParitySectionKind.Memory
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
                verticalAlignment = Alignment.CenterVertically,
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
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
                if (isMemory && canEdit) {
                    DesktopDefaultButton(onClick = onNewBlock) {
                        DesktopButtonContent(text = "New block")
                    }
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
                    val clickable = isMemory && canEdit && item is MemoryParityItem.MemoryBlock
                    MemoryItemRow(
                        item = item,
                        onClick = if (clickable) {
                            { onBlockClick(item as MemoryParityItem.MemoryBlock) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
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

private sealed interface BlockEditorTarget {
    data object New : BlockEditorTarget
    data class Existing(val label: String, val blockId: String?) : BlockEditorTarget
}

/** Right-side panel for viewing/editing a core-memory block (CRUD). */
@Composable
private fun BlockEditorPanel(
    target: BlockEditorTarget,
    agentId: String,
    blockApi: DesktopBlockApi,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = target is BlockEditorTarget.New
    var label by remember(target) {
        mutableStateOf((target as? BlockEditorTarget.Existing)?.label.orEmpty())
    }
    var value by remember(target) { mutableStateOf("") }
    var blockId by remember(target) {
        mutableStateOf((target as? BlockEditorTarget.Existing)?.blockId)
    }
    var loading by remember(target) { mutableStateOf(target is BlockEditorTarget.Existing) }
    var busy by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    // When an existing block fails to load, the editor is read-only — saving the
    // empty value would clobber the real block content on the server.
    var loadFailed by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target) {
        if (target is BlockEditorTarget.Existing) {
            val id = target.blockId
            if (id.isNullOrBlank()) {
                error = "This block has no id and can't be edited."
                loadFailed = true
                loading = false
            } else {
                runCatching { blockApi.getBlockById(id) }
                    .onSuccess {
                        value = it.value
                        blockId = it.id.value
                        loading = false
                    }
                    .onFailure {
                        error = it.message ?: "Could not load block"
                        loadFailed = true
                        loading = false
                    }
            }
        }
    }

    Column(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isNew) "New memory block" else label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable(onClick = onDismiss),
            )
        }

        if (isNew) {
            Text("Label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var labelField by remember { mutableStateOf(TextFieldValue("")) }
            JewelTextField(
                value = labelField,
                onValueChange = { labelField = it; label = it.text },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text("Value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            DesktopTextArea(
                value = value,
                onValueChange = { value = it },
                enabled = !busy && !loadFailed,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = "Block contents…",
                decorationBoxModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentBlockId = blockId
            if (!isNew && currentBlockId != null) {
                DesktopOutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { blockApi.deleteBlockById(currentBlockId) }
                                .onSuccess { onChanged() }
                                .onFailure { error = it.message ?: "Delete failed"; busy = false }
                        }
                    },
                    enabled = !busy,
                ) { DesktopButtonContent(text = "Delete") }
            }
            Box(modifier = Modifier.weight(1f))
            DesktopOutlinedButton(onClick = onDismiss, enabled = !busy) {
                DesktopButtonContent(text = "Cancel")
            }
            DesktopDefaultButton(
                onClick = {
                    if (label.isBlank()) {
                        error = "Label is required"
                    } else {
                        busy = true; error = null
                        val id = blockId
                        scope.launch {
                            runCatching {
                                if (isNew) {
                                    blockApi.createAndAttachBlock(agentId, label.trim(), value)
                                } else if (id != null) {
                                    blockApi.updateBlockById(id, value)
                                } else {
                                    error("Missing block id")
                                }
                            }
                                .onSuccess { onChanged() }
                                .onFailure { error = it.message ?: "Save failed"; busy = false }
                        }
                    }
                },
                enabled = !busy && !loading && !loadFailed,
            ) { DesktopButtonContent(text = if (busy) "Saving…" else "Save") }
        }
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

@Composable
private fun MemoryCategory.categoryColor(): Color = when (this) {
    MemoryCategory.Persona -> MaterialTheme.customColors.categoryPersonaColor
    MemoryCategory.Human -> MaterialTheme.customColors.categoryHumanColor
    MemoryCategory.Onboarding -> MaterialTheme.customColors.categoryOnboardingColor
    MemoryCategory.Project -> MaterialTheme.customColors.categoryProjectColor
    MemoryCategory.Archival -> MaterialTheme.customColors.categoryArchivalColor
}
