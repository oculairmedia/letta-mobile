package com.letta.mobile.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.graph.GraphDimens

/**
 * Theme colours the memory graph paints with, resolved once per composition so the Canvas draw
 * pass stays a plain lookup. Every node kind has its own hue (the legend's swatch is the node's
 * colour); a channel's live status rides on a small dot, not on the node's hue.
 */
@Immutable
data class MemoryGraphPalette(
    val kinds: Map<MemoryGraphNodeKind, Color>,
    val statuses: Map<MemoryChannelStatus, Color>,
    val edge: Color,
    val nodeRing: Color,
    val selectionRing: Color,
    val track: Color,
    val label: Color,
    val labelMuted: Color,
    val labelChip: Color,
) {
    fun nodeColor(node: MemoryGraphNode): Color = kindColor(node.kind)

    fun kindColor(kind: MemoryGraphNodeKind): Color = kinds[kind] ?: label

    fun statusColor(status: MemoryChannelStatus): Color = statuses[status] ?: labelMuted
}

@Composable
fun rememberMemoryGraphPalette(): MemoryGraphPalette {
    val scheme = MaterialTheme.colorScheme
    val custom = MaterialTheme.customColors
    return MemoryGraphPalette(
        kinds = mapOf(
            MemoryGraphNodeKind.Agent to scheme.primary,
            MemoryGraphNodeKind.Backend to scheme.primary,
            MemoryGraphNodeKind.Skill to custom.agentAColor.orElse(scheme.secondary),
            MemoryGraphNodeKind.Memory to custom.agentBColor.orElse(scheme.tertiary),
            MemoryGraphNodeKind.Schedule to custom.runningColor.orElse(scheme.secondary),
            MemoryGraphNodeKind.Channel to custom.agentCColor.orElse(scheme.error),
        ),
        statuses = mapOf(
            MemoryChannelStatus.Connected to custom.onlineColor.orElse(scheme.primary),
            MemoryChannelStatus.Connecting to custom.reconnectingColor.orElse(scheme.tertiary),
            MemoryChannelStatus.Idle to scheme.onSurfaceVariant,
            MemoryChannelStatus.Disconnected to custom.offlineColor.orElse(scheme.error),
        ),
        edge = scheme.outline.copy(alpha = GraphDimens.EDGE_ALPHA),
        nodeRing = scheme.surface,
        selectionRing = scheme.onSurface,
        track = scheme.outlineVariant,
        label = scheme.onSurface,
        labelMuted = scheme.onSurfaceVariant,
        labelChip = scheme.surface.copy(alpha = GraphDimens.LABEL_CHIP_ALPHA),
    )
}

private fun Color.orElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

fun memoryNodeKindLabel(kind: MemoryGraphNodeKind): String = when (kind) {
    MemoryGraphNodeKind.Agent -> "Agent"
    MemoryGraphNodeKind.Backend -> "Backend"
    MemoryGraphNodeKind.Skill -> "Skill"
    MemoryGraphNodeKind.Memory -> "Memory"
    MemoryGraphNodeKind.Schedule -> "Schedule"
    MemoryGraphNodeKind.Channel -> "Channel"
}
