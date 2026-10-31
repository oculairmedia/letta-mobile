package com.letta.mobile.ui.memory

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.graph.MemoryNodeContent
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageState

/** What the node card renders: the selection plus what the graph and overview know about it. */
@Immutable
internal data class MemoryNodeCardState(
    val selection: MemoryNodeSelection,
    val accent: Color,
    /** The overview item behind the node (block, schedule, channel, skill); null for the agent. */
    val item: MemoryParityItem? = null,
    val summary: MemoryParitySummary = MemoryParitySummary(),
    /** The node's neighbours, for the agent's block list and link counts. */
    val related: List<MemoryRelatedNode> = emptyList(),
) {
    /**
     * The subtitle, unless it only repeats the title or a placeholder ("Selected agent"), or is a
     * block's "Limit 5000 chars" fallback that the size meter already says.
     */
    val meaningfulSubtitle: String?
        get() = selection.detail.subtitle.trim().takeIf { subtitle ->
            subtitle.isNotEmpty() && subtitle != selection.detail.title && subtitle !in PLACEHOLDER_SUBTITLES &&
                !(item is MemoryParityItem.MemoryBlock && subtitle.startsWith("Limit "))
        }

    /** Characters a block holds now: the loaded value, else the overview's count. */
    val blockLength: Int?
        get() = (selection.content as? MemoryNodeContent.Loaded)?.value?.length
            ?: (item as? MemoryParityItem.MemoryBlock)?.length

    private companion object {
        val PLACEHOLDER_SUBTITLES = setOf("Selected agent", "Active backend", "Core memory")
    }
}

/** A linked node as the agent card lists it: identity plus, for a block, its size against its limit. */
@Immutable
internal data class MemoryRelatedNode(
    val nodeId: String,
    val title: String,
    val kind: MemoryGraphNodeKind,
    val length: Int? = null,
    val limit: Int? = null,
)

internal object MemoryNodeCards {
    fun build(state: MemoryPageState, selection: MemoryNodeSelection, palette: MemoryGraphPalette): MemoryNodeCardState {
        val nodeId = selection.detail.nodeId
        val node = state.view.node(nodeId)
        val items = state.parity.memory.sections.flatMap { it.items }
        val related = state.view.edges
            .mapNotNull { edge -> if (edge.fromId == nodeId) edge.toId else if (edge.toId == nodeId) edge.fromId else null }
            .mapNotNull(state.view::node)
            .map { relatedOf(it, items) }
        return MemoryNodeCardState(
            selection = selection,
            accent = node?.let(palette::nodeColor) ?: palette.kindColor(selection.detail.kind),
            item = node?.let { itemOf(it, items) },
            summary = state.parity.memory.summary,
            related = related,
        )
    }

    private fun relatedOf(node: MemoryGraphNode, items: List<MemoryParityItem>): MemoryRelatedNode {
        val block = itemOf(node, items) as? MemoryParityItem.MemoryBlock
        return MemoryRelatedNode(node.id, node.title, node.kind, block?.length, block?.limit)
    }

    private fun itemOf(node: MemoryGraphNode, items: List<MemoryParityItem>): MemoryParityItem? =
        items.firstOrNull { it.id == node.sourceItemId && it.matches(node.kind) }

    private fun MemoryParityItem.matches(kind: MemoryGraphNodeKind): Boolean = when (this) {
        is MemoryParityItem.MemoryBlock -> kind == MemoryGraphNodeKind.Memory
        is MemoryParityItem.Schedule -> kind == MemoryGraphNodeKind.Schedule
        is MemoryParityItem.Channel -> kind == MemoryGraphNodeKind.Channel
        is MemoryParityItem.Skill -> kind == MemoryGraphNodeKind.Skill
    }
}
