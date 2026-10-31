package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViewport
import com.letta.mobile.ui.theme.graph.GraphDimens

/** What one draw pass reads besides the live positions: the graph, its looks and the focus. */
internal class GraphScene(
    val view: MemoryGraphView,
    val neighbours: Map<String, List<String>>,
    val looks: Map<String, MemoryNodeLook>,
    val labels: Map<String, MemoryNodeLabel>,
    val palette: MemoryGraphPalette,
    val selectedId: String?,
    val hoveredId: String?,
)

/** Everything one draw pass reads, resolved once per frame. */
internal class GraphFrame(
    val scene: GraphScene,
    private val layout: MemoryGraphLayout,
    private val viewport: MemoryGraphViewport,
) {
    /** The hovered node, else the selected one: it and its neighbours stay bright, the rest dim. */
    val focusId: String? = scene.hoveredId ?: scene.selectedId

    private val focusSet: Set<String> = focusId?.let { id -> scene.neighbours[id].orEmpty().toSet() + id }.orEmpty()

    /** Few enough nodes that every label fits; otherwise only roots, the focus and zoomed-in views label. */
    private val labelsEverything = scene.view.nodes.size <= ALL_LABELS_NODE_LIMIT || viewport.scale >= LABEL_ZOOM_SCALE

    fun screen(nodeId: String): Offset? = layout[nodeId]?.let(viewport::toScreen)?.let { Offset(it.x, it.y) }

    fun radiusOf(nodeId: String): Float = (scene.looks[nodeId]?.radius ?: MemoryNodeLooks.SMALL_RADIUS) * viewport.scale

    fun alphaOf(nodeId: String): Float = if (focusId == null || nodeId in focusSet) 1f else GraphDimens.DIMMED_ALPHA

    fun edgeLit(fromId: String, toId: String): Boolean = focusId != null && (fromId == focusId || toId == focusId)

    fun showsLabel(node: MemoryGraphNode): Boolean =
        labelsEverything || node.id in focusSet || node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend
}

internal fun DrawScope.drawGraph(frame: GraphFrame) {
    drawEdges(frame)
    frame.scene.view.nodes.forEach { node -> frame.screen(node.id)?.let { drawNode(frame, node, it) } }
    // Labels last so no edge or node is drawn over text; the focus label goes on top of all.
    frame.scene.view.nodes.filter { it.id != frame.focusId }.forEach { drawLabel(frame, it) }
    frame.focusId?.let(frame.scene.view::node)?.let { drawLabel(frame, it) }
}

/** Soft quadratic curves bowed a little to one side, so parallel spokes read as a fan. */
private fun DrawScope.drawEdges(frame: GraphFrame) {
    val path = Path()
    frame.scene.view.edges.forEach { edge ->
        val from = frame.screen(edge.fromId) ?: return@forEach
        val to = frame.screen(edge.toId) ?: return@forEach
        path.reset()
        path.moveTo(from.x, from.y)
        val control = bowControl(from, to)
        path.quadraticTo(control.x, control.y, to.x, to.y)
        val look = edgeLook(frame, edge.fromId, edge.toId)
        drawPath(path = path, color = look.colour, alpha = look.alpha, style = Stroke(width = look.width.toPx(), cap = StrokeCap.Round))
    }
}

/** How one edge draws: lit in the far node's colour when it touches the focus, dimmed when another node has it. */
private class EdgeLook(val colour: Color, val alpha: Float, val width: Dp)

private fun edgeLook(frame: GraphFrame, fromId: String, toId: String): EdgeLook {
    val palette = frame.scene.palette
    if (frame.edgeLit(fromId, toId)) {
        val far = frame.scene.view.node(toId)?.let(palette::nodeColor) ?: palette.edge
        return EdgeLook(far.copy(alpha = GraphDimens.FOCUSED_EDGE_ALPHA), 1f, GraphDimens.edgeFocused)
    }
    val alpha = if (frame.focusId != null) GraphDimens.DIMMED_EDGE_ALPHA else 1f
    return EdgeLook(palette.edge, alpha, GraphDimens.edge)
}

private fun bowControl(from: Offset, to: Offset): Offset {
    val mid = (from + to) / 2f
    val dx = to.x - from.x
    val dy = to.y - from.y
    return Offset(mid.x - dy * EDGE_BOW, mid.y + dx * EDGE_BOW)
}

/** One node as this frame paints it: where, how big, in what colour, how bright. */
private class NodePaint(val colour: Color, val centre: Offset, val radius: Float, val alpha: Float)

private fun DrawScope.drawNode(frame: GraphFrame, node: MemoryGraphNode, centre: Offset) {
    val palette = frame.scene.palette
    val paint = NodePaint(palette.nodeColor(node), centre, frame.radiusOf(node.id), frame.alphaOf(node.id))
    if (node.id == frame.focusId) {
        drawCircle(paint.colour.copy(alpha = GraphDimens.HALO_ALPHA), radius = paint.radius + GraphDimens.halo.toPx(), center = centre)
    }
    if (node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend) {
        drawRootNode(paint)
    } else {
        drawCircle(paint.colour, radius = paint.radius, center = centre, alpha = paint.alpha)
        drawCircle(palette.nodeRing, radius = paint.radius, center = centre, alpha = paint.alpha, style = Stroke(width = density))
    }
    frame.scene.looks[node.id]?.usage?.let { drawUsageArc(palette.track, paint, it) }
    node.status?.let { drawStatusDot(palette, it, paint) }
    if (node.id == frame.scene.selectedId) {
        val ring = Stroke(width = GraphDimens.selectionRing.toPx())
        drawCircle(palette.selectionRing, radius = paint.radius + GraphDimens.selectionGap.toPx(), center = centre, style = ring)
    }
}

/** The agent reads as the hub: a tinted disc, a solid rim and a solid core. */
private fun DrawScope.drawRootNode(paint: NodePaint) {
    val (colour, centre) = paint.colour to paint.centre
    drawCircle(colour.copy(alpha = ROOT_FILL_ALPHA), radius = paint.radius, center = centre, alpha = paint.alpha)
    drawCircle(colour, radius = paint.radius, center = centre, alpha = paint.alpha, style = Stroke(width = GraphDimens.rootRim.toPx()))
    drawCircle(colour, radius = paint.radius * ROOT_CORE_FRACTION, center = centre, alpha = paint.alpha)
}

/** A block's fill against its limit, as an arc just outside the node, starting at twelve o'clock. */
private fun DrawScope.drawUsageArc(track: Color, paint: NodePaint, usage: Float) {
    val ring = paint.radius + GraphDimens.usageGap.toPx()
    val topLeft = Offset(paint.centre.x - ring, paint.centre.y - ring)
    val size = Size(ring * 2f, ring * 2f)
    val stroke = Stroke(width = GraphDimens.usageStroke.toPx(), cap = StrokeCap.Round)
    drawArc(track, 0f, FULL_SWEEP, useCenter = false, topLeft = topLeft, size = size, alpha = paint.alpha, style = stroke)
    val sweep = FULL_SWEEP * usage
    drawArc(paint.colour, START_ANGLE, sweep, useCenter = false, topLeft = topLeft, size = size, alpha = paint.alpha, style = stroke)
}

private fun DrawScope.drawStatusDot(palette: MemoryGraphPalette, status: MemoryChannelStatus, paint: NodePaint) {
    val dot = Offset(paint.centre.x + paint.radius * DOT_OFFSET, paint.centre.y - paint.radius * DOT_OFFSET)
    val dotRadius = GraphDimens.statusDot.toPx()
    drawCircle(palette.nodeRing, radius = dotRadius + density, center = dot, alpha = paint.alpha)
    drawCircle(palette.statusColor(status), radius = dotRadius, center = dot, alpha = paint.alpha)
}

private fun DrawScope.drawLabel(frame: GraphFrame, node: MemoryGraphNode) {
    if (!frame.showsLabel(node)) return
    val label = frame.scene.labels[node.id] ?: return
    val centre = frame.screen(node.id) ?: return
    val text = if (node.id == frame.focusId) label.full else label.short
    val pad = Offset(GraphDimens.labelChipPadX.toPx(), GraphDimens.labelChipPadY.toPx())
    val chipSize = Size(text.size.width + pad.x * 2f, text.size.height + pad.y * 2f)
    val chip = labelChipTopLeft(frame, node, centre, chipSize)
    val alpha = frame.alphaOf(node.id)
    drawRoundRect(frame.scene.palette.labelChip, chip, chipSize, CornerRadius(chipSize.height / 2f), alpha = alpha)
    drawText(text, topLeft = chip + pad, alpha = alpha)
}

/** The chip hung off the node toward its emptiest side, slid back inside the canvas if it would run off. */
private fun DrawScope.labelChipTopLeft(frame: GraphFrame, node: MemoryGraphNode, centre: Offset, chip: Size): Offset {
    val neighbours = frame.scene.neighbours[node.id].orEmpty().mapNotNull(frame::screen)
    val direction = MemoryLabelPlacement.direction(centre, neighbours)
    val arcGap = if (frame.scene.looks[node.id]?.usage != null) GraphDimens.usageGap.toPx() else 0f
    val reach = frame.radiusOf(node.id) + GraphDimens.labelGap.toPx() + arcGap
    val placed = MemoryLabelPlacement.topLeft(centre, reach, direction, chip)
    val inset = GraphDimens.labelGap.toPx()
    return Offset(
        placed.x.coerceIn(inset, (size.width - chip.width - inset).coerceAtLeast(inset)),
        placed.y.coerceIn(inset, (size.height - chip.height - inset).coerceAtLeast(inset)),
    )
}

/** Undirected adjacency, built once per view: label placement and focus both read it. */
internal fun MemoryGraphView.adjacency(): Map<String, List<String>> {
    val neighbours = HashMap<String, MutableList<String>>()
    edges.forEach { edge ->
        neighbours.getOrPut(edge.fromId) { mutableListOf() } += edge.toId
        neighbours.getOrPut(edge.toId) { mutableListOf() } += edge.fromId
    }
    return neighbours
}

private const val ALL_LABELS_NODE_LIMIT = 60
private const val LABEL_ZOOM_SCALE = 1.6f
private const val EDGE_BOW = 0.12f
private const val ROOT_FILL_ALPHA = 0.22f
private const val ROOT_CORE_FRACTION = 0.42f
private const val FULL_SWEEP = 360f
private const val START_ANGLE = -90f
private const val DOT_OFFSET = 0.72f
