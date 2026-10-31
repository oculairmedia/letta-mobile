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
    val stroke = GraphDimens.edge.toPx()
    frame.scene.view.edges.forEach { edge ->
        val from = frame.screen(edge.fromId) ?: return@forEach
        val to = frame.screen(edge.toId) ?: return@forEach
        val lit = frame.edgeLit(edge.fromId, edge.toId)
        val dimmed = frame.focusId != null && !lit
        val litColour = frame.scene.view.node(edge.toId)?.let(frame.scene.palette::nodeColor) ?: frame.scene.palette.edge
        path.reset()
        path.moveTo(from.x, from.y)
        val control = bowControl(from, to)
        path.quadraticTo(control.x, control.y, to.x, to.y)
        drawPath(
            path = path,
            color = if (lit) litColour.copy(alpha = GraphDimens.FOCUSED_EDGE_ALPHA) else frame.scene.palette.edge,
            alpha = if (dimmed) GraphDimens.DIMMED_EDGE_ALPHA else 1f,
            style = Stroke(width = if (lit) GraphDimens.edgeFocused.toPx() else stroke, cap = StrokeCap.Round),
        )
    }
}

private fun bowControl(from: Offset, to: Offset): Offset {
    val mid = (from + to) / 2f
    val dx = to.x - from.x
    val dy = to.y - from.y
    return Offset(mid.x - dy * EDGE_BOW, mid.y + dx * EDGE_BOW)
}

private fun DrawScope.drawNode(frame: GraphFrame, node: MemoryGraphNode, centre: Offset) {
    val palette = frame.scene.palette
    val colour = palette.nodeColor(node)
    val radius = frame.radiusOf(node.id)
    val alpha = frame.alphaOf(node.id)
    val focused = node.id == frame.focusId
    if (focused) {
        drawCircle(colour.copy(alpha = GraphDimens.HALO_ALPHA), radius = radius + GraphDimens.halo.toPx(), center = centre)
    }
    if (node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend) {
        drawRootNode(colour, centre, radius, alpha)
    } else {
        drawCircle(colour, radius = radius, center = centre, alpha = alpha)
        drawCircle(palette.nodeRing, radius = radius, center = centre, alpha = alpha, style = Stroke(width = density))
    }
    frame.scene.looks[node.id]?.usage?.let { drawUsageArc(palette, colour, centre, radius, it, alpha) }
    node.status?.let { drawStatusDot(palette, it, centre, radius, alpha) }
    if (node.id == frame.scene.selectedId) {
        drawCircle(
            palette.selectionRing,
            radius = radius + GraphDimens.selectionGap.toPx(),
            center = centre,
            style = Stroke(width = GraphDimens.selectionRing.toPx()),
        )
    }
}

/** The agent reads as the hub: a tinted disc, a solid rim and a solid core. */
private fun DrawScope.drawRootNode(colour: Color, centre: Offset, radius: Float, alpha: Float) {
    drawCircle(colour.copy(alpha = ROOT_FILL_ALPHA), radius = radius, center = centre, alpha = alpha)
    drawCircle(colour, radius = radius, center = centre, alpha = alpha, style = Stroke(width = GraphDimens.rootRim.toPx()))
    drawCircle(colour, radius = radius * ROOT_CORE_FRACTION, center = centre, alpha = alpha)
}

/** A block's fill against its limit, as an arc just outside the node, starting at twelve o'clock. */
private fun DrawScope.drawUsageArc(palette: MemoryGraphPalette, colour: Color, centre: Offset, radius: Float, usage: Float, alpha: Float) {
    val ring = radius + GraphDimens.usageGap.toPx()
    val topLeft = Offset(centre.x - ring, centre.y - ring)
    val size = Size(ring * 2f, ring * 2f)
    val stroke = Stroke(width = GraphDimens.usageStroke.toPx(), cap = StrokeCap.Round)
    drawArc(palette.track, 0f, FULL_SWEEP, useCenter = false, topLeft = topLeft, size = size, alpha = alpha, style = stroke)
    drawArc(colour, START_ANGLE, FULL_SWEEP * usage, useCenter = false, topLeft = topLeft, size = size, alpha = alpha, style = stroke)
}

private fun DrawScope.drawStatusDot(
    palette: MemoryGraphPalette,
    status: MemoryChannelStatus,
    centre: Offset,
    radius: Float,
    alpha: Float,
) {
    val dot = Offset(centre.x + radius * DOT_OFFSET, centre.y - radius * DOT_OFFSET)
    val dotRadius = GraphDimens.statusDot.toPx()
    drawCircle(palette.nodeRing, radius = dotRadius + density, center = dot, alpha = alpha)
    drawCircle(palette.statusColor(status), radius = dotRadius, center = dot, alpha = alpha)
}

private fun DrawScope.drawLabel(frame: GraphFrame, node: MemoryGraphNode) {
    if (!frame.showsLabel(node)) return
    val label = frame.scene.labels[node.id] ?: return
    val centre = frame.screen(node.id) ?: return
    val text = if (node.id == frame.focusId) label.full else label.short
    val neighbours = frame.scene.neighbours[node.id].orEmpty().mapNotNull(frame::screen)
    val direction = MemoryLabelPlacement.direction(centre, neighbours)
    val padX = GraphDimens.labelChipPadX.toPx()
    val padY = GraphDimens.labelChipPadY.toPx()
    val width = text.size.width + padX * 2f
    val height = text.size.height + padY * 2f
    val gap = GraphDimens.labelGap.toPx() + if (frame.scene.looks[node.id]?.usage != null) GraphDimens.usageGap.toPx() else 0f
    val placed = MemoryLabelPlacement.topLeft(centre, frame.radiusOf(node.id), gap, direction, width, height)
    // Slide a label that would run off the canvas back inside it rather than clip it.
    val inset = GraphDimens.labelGap.toPx()
    val chip = Offset(
        placed.x.coerceIn(inset, (size.width - width - inset).coerceAtLeast(inset)),
        placed.y.coerceIn(inset, (size.height - height - inset).coerceAtLeast(inset)),
    )
    val alpha = frame.alphaOf(node.id)
    drawRoundRect(
        color = frame.scene.palette.labelChip,
        topLeft = chip,
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
        alpha = alpha,
    )
    drawText(text, topLeft = Offset(chip.x + padX, chip.y + padY), alpha = alpha)
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
