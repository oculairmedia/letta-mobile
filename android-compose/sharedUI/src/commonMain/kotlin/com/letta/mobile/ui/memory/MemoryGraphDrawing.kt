package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphNodeMetrics
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViewport

/** Everything one draw pass reads, resolved once per frame. */
internal class GraphFrame(
    val params: MemoryGraphCanvasParams,
    private val viewport: MemoryGraphViewport,
    val palette: MemoryGraphPalette,
    private val labels: Map<String, TextLayoutResult>,
    density: Float,
) {
    val highlighted: Set<String> = params.selectedNodeId?.let { selected ->
        params.view.edges.filter { it.fromId == selected || it.toId == selected }.map { it.id }.toSet()
    }.orEmpty()

    /** Zoomed in far enough that every node can carry its label legibly. */
    private val zoomedInForLabels: Boolean = viewport.scale >= density * LABEL_ZOOM_THRESHOLD

    fun screen(nodeId: String): Offset? =
        params.layout[nodeId]?.let(viewport::toScreen)?.let { Offset(it.x, it.y) }

    fun radiusOf(node: MemoryGraphNode): Float =
        MemoryGraphNodeMetrics.radius(params.view.degreeOf(node.id)) * viewport.scale

    fun isSelected(node: MemoryGraphNode): Boolean = node.id == params.selectedNodeId

    fun labelFor(node: MemoryGraphNode): TextLayoutResult? = labels[node.id]?.takeIf { showsLabel(node) }

    private fun showsLabel(node: MemoryGraphNode): Boolean =
        zoomedInForLabels || isSelected(node) || isHub(node, params.view)
}

/** One node positioned on screen for this frame. */
private class NodePlacement(val node: MemoryGraphNode, val centre: Offset, val radius: Float)

internal fun DrawScope.drawEdges(frame: GraphFrame) {
    val stroke = EDGE_STROKE_DP * density
    frame.params.view.edges.forEach { edge ->
        val from = frame.screen(edge.fromId) ?: return@forEach
        val to = frame.screen(edge.toId) ?: return@forEach
        val lit = edge.id in frame.highlighted
        drawLine(
            color = if (lit) frame.palette.edgeHighlight else frame.palette.edge,
            start = from,
            end = to,
            strokeWidth = if (lit) stroke * 2f else stroke,
        )
    }
}

internal fun DrawScope.drawNodes(frame: GraphFrame, labelGapPx: Float) {
    frame.params.view.nodes.forEach { node ->
        val centre = frame.screen(node.id) ?: return@forEach
        drawNode(frame, NodePlacement(node, centre, frame.radiusOf(node)), labelGapPx)
    }
}

private fun DrawScope.drawNode(frame: GraphFrame, placement: NodePlacement, labelGapPx: Float) {
    drawNodeDot(frame, placement)
    if (frame.isSelected(placement.node)) drawSelectionRing(frame, placement)
    frame.labelFor(placement.node)?.let { drawNodeLabel(it, placement, labelGapPx) }
}

private fun DrawScope.drawNodeDot(frame: GraphFrame, placement: NodePlacement) {
    drawCircle(color = frame.palette.nodeColor(placement.node), radius = placement.radius, center = placement.centre)
    drawCircle(color = frame.palette.nodeRing, radius = placement.radius, center = placement.centre, style = Stroke(width = density))
}

private fun DrawScope.drawSelectionRing(frame: GraphFrame, placement: NodePlacement) {
    drawCircle(
        color = frame.palette.selectionRing,
        radius = placement.radius + SELECTION_RING_GAP_DP * density,
        center = placement.centre,
        style = Stroke(width = SELECTION_RING_DP * density),
    )
}

private fun DrawScope.drawNodeLabel(label: TextLayoutResult, placement: NodePlacement, labelGapPx: Float) {
    val topLeft = Offset(placement.centre.x - label.size.width / 2f, placement.centre.y + placement.radius + labelGapPx)
    drawText(label, topLeft = topLeft)
}

/** Well-connected nodes and the root read as primary: bolder, always-on labels. */
internal fun isHub(node: MemoryGraphNode, view: MemoryGraphView): Boolean =
    view.degreeOf(node.id) >= HUB_DEGREE || node.kind in ROOT_KINDS

private val ROOT_KINDS = setOf(MemoryGraphNodeKind.Agent, MemoryGraphNodeKind.Backend)
private const val EDGE_STROKE_DP = 1.2f
private const val SELECTION_RING_DP = 2.5f
private const val SELECTION_RING_GAP_DP = 3f
private const val HUB_DEGREE = 3
private const val LABEL_ZOOM_THRESHOLD = 0.7f
