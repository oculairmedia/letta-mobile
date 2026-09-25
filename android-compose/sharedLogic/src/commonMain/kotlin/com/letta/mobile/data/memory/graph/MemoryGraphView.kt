package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph

/**
 * The visible slice of a [MemoryParityGraph]: nodes whose kind is not filtered
 * out, the edges between them, and each node's degree (hubs render bigger).
 * This is the desktop "Entity Types" filter, lifted out of the Kuiver panel.
 */
@Immutable
data class MemoryGraphView(
    val nodes: List<MemoryGraphNode> = emptyList(),
    val edges: List<MemoryGraphEdge> = emptyList(),
    val degreeById: Map<String, Int> = emptyMap(),
    val kindsPresent: List<MemoryGraphNodeKind> = emptyList(),
    val disabledKinds: Set<MemoryGraphNodeKind> = emptySet(),
    val summaryLabel: String = "",
) {
    val isEmpty: Boolean
        get() = nodes.isEmpty()

    fun degreeOf(nodeId: String): Int = degreeById[nodeId] ?: 0

    fun node(nodeId: String): MemoryGraphNode? = nodes.firstOrNull { it.id == nodeId }

    fun isKindEnabled(kind: MemoryGraphNodeKind): Boolean = kind !in disabledKinds

    /** Same topology (what the layout depends on), ignoring titles/subtitles. */
    fun sameTopologyAs(other: MemoryGraphView): Boolean =
        nodes.map { it.id } == other.nodes.map { it.id } &&
            edges.map { it.fromId to it.toId } == other.edges.map { it.fromId to it.toId }
}

object MemoryGraphViews {
    fun kindsPresent(graph: MemoryParityGraph): List<MemoryGraphNodeKind> =
        MemoryGraphNodeKind.entries.filter { kind -> graph.nodes.any { it.kind == kind } }

    fun build(graph: MemoryParityGraph, disabledKinds: Set<MemoryGraphNodeKind>): MemoryGraphView {
        val present = kindsPresent(graph)
        val effectiveDisabled = disabledKinds.intersect(present.toSet())
        val visibleNodes = graph.nodes.filter { it.kind !in effectiveDisabled }
        val visibleIds = visibleNodes.mapTo(HashSet()) { it.id }
        val visibleEdges = graph.edges.filter { it.fromId in visibleIds && it.toId in visibleIds }
        return MemoryGraphView(
            nodes = visibleNodes,
            edges = visibleEdges,
            degreeById = degrees(visibleEdges),
            kindsPresent = present,
            disabledKinds = effectiveDisabled,
            summaryLabel = graph.summaryLabel,
        )
    }

    /**
     * Toggle one kind. The last enabled kind cannot be switched off, so the
     * graph is never blanked by the filter (desktop semantics).
     */
    fun toggle(
        disabled: Set<MemoryGraphNodeKind>,
        kindsPresent: List<MemoryGraphNodeKind>,
        kind: MemoryGraphNodeKind,
    ): Set<MemoryGraphNodeKind> {
        if (kind in disabled) return disabled - kind
        val enabledCount = kindsPresent.count { it !in disabled }
        return if (enabledCount > 1) disabled + kind else disabled
    }

    private fun degrees(edges: List<MemoryGraphEdge>): Map<String, Int> {
        val degreeById = HashMap<String, Int>()
        edges.forEach { edge ->
            degreeById[edge.fromId] = (degreeById[edge.fromId] ?: 0) + 1
            degreeById[edge.toId] = (degreeById[edge.toId] ?: 0) + 1
        }
        return degreeById
    }
}
