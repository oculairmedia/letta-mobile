package com.letta.mobile.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphHitTarget
import com.letta.mobile.data.memory.graph.MemoryGraphHitTest
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphNodeMetrics
import com.letta.mobile.data.memory.graph.MemoryGraphSize
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.exp

@Immutable
internal data class MemoryGraphCanvasParams(
    val view: MemoryGraphView,
    val layout: MemoryGraphLayout,
    val selectedNodeId: String?,
    val onNodeTap: (String?) -> Unit,
)

/**
 * The full memory graph on one Canvas: straight edges, degree-sized dots in
 * category/kind colours, constant-size labels. Pinch/drag (or wheel/drag) moves
 * the viewport; a tap resolves the nearest node through the shared hit test,
 * whose reach never drops below a 48dp touch target.
 */
@Composable
internal fun MemoryGraphCanvas(
    params: MemoryGraphCanvasParams,
    viewportState: MemoryGraphViewportState,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    val labels = rememberNodeLabels(params.view, palette)
    val density = LocalDensity.current
    val minTouchPx = with(density) { MemoryGraphNodeMetrics.MIN_TOUCH_RADIUS * this.density }
    val labelGapPx = with(density) { LettaDimens.Space.xs.toPx() }
    LaunchedEffect(params.layout) { viewportState.sync(params.layout, viewportState.size) }
    Canvas(
        modifier = modifier
            .onSizeChanged { viewportState.sync(params.layout, MemoryGraphSize(it.width.toFloat(), it.height.toFloat())) }
            .graphGestures(viewportState)
            .pointerInput(params.view, params.layout, params.onNodeTap) {
                // No onDoubleTap: it would hold every single tap for the
                // double-tap timeout. Zoom lives on pinch, wheel and the buttons.
                detectTapGestures { offset ->
                    val target = MemoryGraphHitTarget(params.view, params.layout, viewportState.viewport, minTouchPx)
                    params.onNodeTap(MemoryGraphHitTest.nodeAt(offset.toPoint(), target))
                }
            }
            .semantics { contentDescription = "Memory graph, ${params.view.nodes.size} nodes" },
    ) {
        val frame = GraphFrame(params, viewportState, palette, labels)
        drawEdges(frame)
        drawNodes(frame, labelGapPx)
    }
}

private class GraphFrame(
    val params: MemoryGraphCanvasParams,
    val viewportState: MemoryGraphViewportState,
    val palette: MemoryGraphPalette,
    val labels: Map<String, TextLayoutResult>,
) {
    val highlighted: Set<String> = params.selectedNodeId?.let { selected ->
        params.view.edges.filter { it.fromId == selected || it.toId == selected }.map { it.id }.toSet()
    }.orEmpty()

    fun screen(nodeId: String): Offset? =
        params.layout[nodeId]?.let(viewportState.viewport::toScreen)?.let { Offset(it.x, it.y) }
}

private fun DrawScope.drawEdges(frame: GraphFrame) {
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

private fun DrawScope.drawNodes(frame: GraphFrame, labelGapPx: Float) {
    val scale = frame.viewportState.viewport.scale
    val showAllLabels = scale >= density * LABEL_ZOOM_THRESHOLD
    frame.params.view.nodes.forEach { node ->
        val centre = frame.screen(node.id) ?: return@forEach
        val radius = MemoryGraphNodeMetrics.radius(frame.params.view.degreeOf(node.id)) * scale
        val selected = node.id == frame.params.selectedNodeId
        drawCircle(color = frame.palette.nodeColor(node), radius = radius, center = centre)
        drawCircle(color = frame.palette.nodeRing, radius = radius, center = centre, style = Stroke(width = density))
        if (selected) {
            drawCircle(
                color = frame.palette.selectionRing,
                radius = radius + SELECTION_RING_GAP_DP * density,
                center = centre,
                style = Stroke(width = SELECTION_RING_DP * density),
            )
        }
        val label = frame.labels[node.id]
        if (label != null && (showAllLabels || selected || isHub(node, frame.params.view))) {
            drawText(label, topLeft = Offset(centre.x - label.size.width / 2f, centre.y + radius + labelGapPx))
        }
    }
}

private fun isHub(node: MemoryGraphNode, view: MemoryGraphView): Boolean =
    view.degreeOf(node.id) >= HUB_DEGREE ||
        node.kind == MemoryGraphNodeKind.Agent ||
        node.kind == MemoryGraphNodeKind.Backend

/** Labels are measured once per graph/theme, not per frame. */
@Composable
private fun rememberNodeLabels(view: MemoryGraphView, palette: MemoryGraphPalette): Map<String, TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val maxWidthPx = with(LocalDensity.current) { LettaDimens.Pane.nodeLabelMaxWidth.roundToPx() }
    return remember(view, palette, typography, maxWidthPx) {
        view.nodes.associate { node ->
            val hub = isHub(node, view)
            val style = if (hub) {
                typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = palette.label)
            } else {
                typography.labelSmall.copy(color = palette.labelMuted)
            }
            node.id to measureLabel(measurer, node.title, style, maxWidthPx)
        }
    }
}

private fun measureLabel(measurer: TextMeasurer, text: String, style: TextStyle, maxWidthPx: Int): TextLayoutResult =
    measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidthPx),
    )

/** Pinch/drag on touch, drag + wheel on desktop. */
private fun Modifier.graphGestures(state: MemoryGraphViewportState): Modifier =
    pointerInput(state) {
        detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
    }.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val factor = exp(-change.scrollDelta.y * WHEEL_ZOOM_RATE)
                state.transform(change.position, Offset.Zero, factor)
                change.consume()
            }
        }
    }

private const val EDGE_STROKE_DP = 1.2f
private const val SELECTION_RING_DP = 2.5f
private const val SELECTION_RING_GAP_DP = 3f
private const val HUB_DEGREE = 3
private const val LABEL_ZOOM_THRESHOLD = 0.7f
private const val WHEEL_ZOOM_RATE = 0.12f
