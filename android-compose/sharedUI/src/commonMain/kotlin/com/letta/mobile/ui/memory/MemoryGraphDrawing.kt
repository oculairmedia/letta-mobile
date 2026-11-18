package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViewport
import com.letta.mobile.ui.theme.graph.GraphDimens
import kotlin.math.round

/** The labels a draw pass places: their measured text, which to show, their fade and the tuning. */
internal class GraphLabels(
    val texts: Map<String, MemoryNodeLabel>,
    val labeler: MemoryGraphLabeler,
    val fade: MemoryLabelFade,
    val tuning: MemoryGraphTuning,
)

/** What one draw pass reads besides the live positions: the graph, its looks and the focus. */
internal class GraphScene(
    val view: MemoryGraphView,
    val neighbours: Map<String, List<String>>,
    val looks: Map<String, MemoryNodeLook>,
    val labels: GraphLabels,
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

    /** What decides this frame's labels besides the graph. */
    val labelContext = MemoryLabelContext(scene.selectedId, scene.hoveredId, viewport.scale, scene.labels.tuning)

    fun screen(nodeId: String): Offset? = layout[nodeId]?.let(viewport::toScreen)?.let { Offset(it.x, it.y) }

    fun radiusOf(node: MemoryGraphNode): Float = (scene.looks[node.id]?.radius ?: MemoryNodeLooks.SMALL_RADIUS) * viewport.scale

    fun alphaOf(node: MemoryGraphNode): Float = if (isLit(node)) 1f else GraphDimens.DIMMED_ALPHA

    /** Nothing has focus, or this node is the focus or one of its neighbours. */
    fun isLit(node: MemoryGraphNode): Boolean = focusId == null || node.id in focusSet

    /** The edge touches the focus. */
    fun edgeLit(edge: MemoryGraphEdge): Boolean = focusId != null && (edge.fromId == focusId || edge.toId == focusId)
}

internal fun DrawScope.drawGraph(frame: GraphFrame) {
    drawEdges(frame)
    frame.scene.view.nodes.forEach { node -> frame.screen(node.id)?.let { drawNode(frame, node, it) } }
    // Labels last so no edge or node is drawn over text; the focus label goes on top of all.
    val labels = frame.scene.labels
    val shown = labels.labeler.shown(frame.labelContext) { node -> labelChip(frame, node)?.rect }
    labels.fade.advance(shown)
    frame.scene.view.nodes.forEach { node -> if (node.id != frame.focusId) drawFadedLabel(frame, node) }
    frame.focusId?.let(frame.scene.view::node)?.let { drawFadedLabel(frame, it) }
}

private fun DrawScope.drawFadedLabel(frame: GraphFrame, node: MemoryGraphNode) {
    val fade = frame.scene.labels.fade.alphaOf(node.id)
    if (fade > 0f) drawLabel(frame, node, fade)
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
        val look = edgeLook(frame, edge)
        drawPath(path = path, color = look.colour, alpha = look.alpha, style = Stroke(width = look.width.toPx(), cap = StrokeCap.Round))
    }
}

/** How one edge draws: lit in the far node's colour when it touches the focus, dimmed when another node has it. */
private class EdgeLook(val colour: Color, val alpha: Float, val width: Dp)

private fun edgeLook(frame: GraphFrame, edge: MemoryGraphEdge): EdgeLook {
    val palette = frame.scene.palette
    if (frame.edgeLit(edge)) {
        val far = frame.scene.view.node(edge.toId)?.let(palette::nodeColor) ?: palette.edge
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
    val paint = NodePaint(palette.nodeColor(node), centre, frame.radiusOf(node), frame.alphaOf(node))
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

/** One label as placed this frame: its text, its chip's box on screen and the text's inset in it. */
private class LabelChip(val text: TextLayoutResult, val topLeft: Offset, val size: Size, val pad: Offset) {
    val rect: Rect get() = Rect(topLeft, size)
}

private fun DrawScope.labelChip(frame: GraphFrame, node: MemoryGraphNode): LabelChip? {
    val label = frame.scene.labels.texts[node.id] ?: return null
    val centre = frame.screen(node.id) ?: return null
    val text = if (node.id == frame.focusId) label.full else label.short
    val pad = Offset(round(GraphDimens.labelChipPadX.toPx()), round(GraphDimens.labelChipPadY.toPx()))
    val chipSize = Size(text.size.width + pad.x * 2f, text.size.height + pad.y * 2f)
    val placed = labelChipTopLeft(frame, node, centre, chipSize) ?: return null
    // Whole device pixels, so text moving with its node never shimmers between sub-pixel positions.
    return LabelChip(text, Offset(round(placed.x), round(placed.y)), chipSize, pad)
}

private fun DrawScope.drawLabel(frame: GraphFrame, node: MemoryGraphNode, fade: Float) {
    val chip = labelChip(frame, node) ?: return
    val alpha = frame.alphaOf(node) * fade
    drawRoundRect(frame.scene.palette.labelChip, chip.topLeft, chip.size, CornerRadius(chip.size.height / 2f), alpha = alpha)
    drawText(chip.text, topLeft = chip.topLeft + chip.pad, alpha = alpha)
}

/**
 * The chip hung off the node toward its emptiest side, slid back inside the canvas if it would run
 * off. A dimmed node's label that would run off is dropped instead, so it never crowds the focus.
 */
private fun DrawScope.labelChipTopLeft(frame: GraphFrame, node: MemoryGraphNode, centre: Offset, chip: Size): Offset? {
    val direction = frame.scene.labels.labeler.direction(node.id) {
        MemoryLabelPlacement.direction(centre, frame.scene.neighbours[node.id].orEmpty().mapNotNull(frame::screen))
    }
    val arcGap = if (frame.scene.looks[node.id]?.usage != null) GraphDimens.usageGap.toPx() else 0f
    val reach = frame.radiusOf(node) + GraphDimens.labelGap.toPx() + arcGap
    val placed = MemoryLabelPlacement.topLeft(centre, reach, direction, chip)
    val inset = GraphDimens.labelGap.toPx()
    val clamped = Offset(
        placed.x.coerceIn(inset, (size.width - chip.width - inset).coerceAtLeast(inset)),
        placed.y.coerceIn(inset, (size.height - chip.height - inset).coerceAtLeast(inset)),
    )
    return clamped.takeIf { it == placed || frame.isLit(node) }
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

private const val EDGE_BOW = 0.12f
private const val ROOT_FILL_ALPHA = 0.22f
private const val ROOT_CORE_FRACTION = 0.42f
private const val FULL_SWEEP = 360f
private const val START_ANGLE = -90f
private const val DOT_OFFSET = 0.72f
