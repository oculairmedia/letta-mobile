package com.letta.mobile.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.graph.MemoryGraphBounds
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryPageState
import kotlinx.coroutines.flow.first

/** What phone framing reads: the page, the live positions, and whether to animate. */
internal class MemoryFraming(val state: MemoryPageState, val live: MemoryGraphLiveLayout, val reducedMotion: Boolean)

/**
 * On a phone, frame the selected node and its neighbours in the strip the bottom sheet leaves
 * (re-framed as the sheet's [coveredPx] settles), and the whole graph again when the sheet closes.
 */
@Composable
internal fun FrameSelectionAboveSheet(framing: MemoryFraming, viewportState: MemoryGraphViewportState, coveredPx: Int) {
    val nodeId = framing.state.selectedNodeId
    var framedSelection by remember { mutableStateOf(false) }
    LaunchedEffect(nodeId, coveredPx) {
        // Wait for the canvas to be measured (and fitted) before framing against its size.
        snapshotFlow { viewportState.size }.first { it.width > 0f && it.height > 0f }
        // The effect can run before this frame's SideEffects: take the sheet's height from here.
        viewportState.insets = viewportState.insets.copy(bottom = coveredPx.toFloat())
        val ids = nodeId?.let(framing.state.view::node)?.let { selectionIds(framing.state, it) }
        when {
            ids != null -> framing.boundsOf(ids)?.let { viewportState.frame(it, framing.reducedMotion) }
            framedSelection -> viewportState.frame(framing.state.layout.bounds, framing.reducedMotion)
        }
        framedSelection = ids != null
    }
}

/** The node and everything it links to. */
private fun selectionIds(state: MemoryPageState, node: MemoryGraphNode): Set<String> =
    state.view.edges.mapNotNullTo(mutableSetOf(node.id)) { edge ->
        when (node.id) {
            edge.fromId -> edge.toId
            edge.toId -> edge.fromId
            else -> null
        }
    }

/** Where [ids] sit: settled live positions, else (mid-bloom) where they will settle. */
private fun MemoryFraming.boundsOf(ids: Set<String>): MemoryGraphBounds? {
    val source = if (live.isSettled) live.layout else state.layout
    val points: List<MemoryGraphPoint> = ids.mapNotNull { source[it] ?: state.layout[it] }
    return points.takeIf { it.isNotEmpty() }?.let(MemoryGraphBounds::of)
}
