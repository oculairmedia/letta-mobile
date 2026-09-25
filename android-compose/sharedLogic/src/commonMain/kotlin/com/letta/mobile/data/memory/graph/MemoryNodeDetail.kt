package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParityState

/** Addresses one core-memory block for a load or a committed write. */
@Immutable
data class MemoryBlockRef(
    val agentId: String,
    val label: String,
    val blockId: String,
)

/**
 * What a selected node's card shows before any async content load: the node's
 * identity plus the overview item's text. Memory nodes also carry a
 * [blockRef] so the full value can be fetched and edited.
 */
@Immutable
data class MemoryNodeDetail(
    val nodeId: String,
    val title: String,
    val kind: MemoryGraphNodeKind,
    val subtitle: String,
    val body: String,
    val metadataLabels: List<String>,
    val blockRef: MemoryBlockRef? = null,
    val readOnly: Boolean = false,
    val limit: Int? = null,
)

object MemoryNodeDetails {
    fun resolve(memory: MemoryParityState, nodeId: String): MemoryNodeDetail? {
        val node = memory.graph.nodes.firstOrNull { it.id == nodeId } ?: return null
        val item = itemFor(memory, node)
        return when (item) {
            is MemoryParityItem.MemoryBlock -> blockDetail(node, item, memory.selectedAgentId)
            null -> bareDetail(node)
            else -> itemDetail(node, item)
        }
    }

    private fun itemFor(memory: MemoryParityState, node: MemoryGraphNode): MemoryParityItem? {
        val sectionKind = node.kind.sectionKind() ?: return null
        val section = memory.sections.firstOrNull { it.kind == sectionKind } ?: return null
        return section.items.firstOrNull { it.id == node.sourceItemId }
    }

    private fun bareDetail(node: MemoryGraphNode) = MemoryNodeDetail(
        nodeId = node.id,
        title = node.title,
        kind = node.kind,
        subtitle = node.subtitle,
        body = node.subtitle,
        metadataLabels = emptyList(),
    )

    private fun itemDetail(node: MemoryGraphNode, item: MemoryParityItem) = MemoryNodeDetail(
        nodeId = node.id,
        title = item.title,
        kind = node.kind,
        subtitle = item.subtitle,
        body = item.detailText,
        metadataLabels = item.metadataLabels,
    )

    private fun blockDetail(
        node: MemoryGraphNode,
        item: MemoryParityItem.MemoryBlock,
        agentId: String?,
    ): MemoryNodeDetail {
        val label = item.label
        return MemoryNodeDetail(
            nodeId = node.id,
            title = item.title,
            kind = node.kind,
            subtitle = item.subtitle,
            body = item.preview.ifBlank { item.detailText },
            metadataLabels = item.metadataLabels,
            blockRef = if (agentId != null && label != null) MemoryBlockRef(agentId, label, item.id) else null,
            readOnly = item.readOnly,
            limit = item.limit,
        )
    }

    private fun MemoryGraphNodeKind.sectionKind(): MemoryParitySectionKind? = when (this) {
        MemoryGraphNodeKind.Skill -> MemoryParitySectionKind.Skills
        MemoryGraphNodeKind.Memory -> MemoryParitySectionKind.Memory
        MemoryGraphNodeKind.Schedule -> MemoryParitySectionKind.Schedules
        MemoryGraphNodeKind.Channel -> MemoryParitySectionKind.Channels
        MemoryGraphNodeKind.Agent, MemoryGraphNodeKind.Backend -> null
    }
}
