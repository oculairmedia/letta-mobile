package com.letta.mobile.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphNodeMetrics
import com.letta.mobile.data.memory.graph.MemoryGraphSize
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.data.memory.graph.MemoryGraphViewport
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

@Immutable
internal data class MemoryGraphCanvasParams(
    val view: MemoryGraphView,
    /** The controller's settled layout: what a fit frames. */
    val layout: MemoryGraphLayout,
    val live: MemoryGraphLiveLayout,
    val looks: Map<String, MemoryNodeLook>,
    /** Below the wide breakpoint: resting labels take the narrower cap so a phone keeps its graph. */
    val compact: Boolean,
    val selectedNodeId: String?,
    val onNodeTap: (String?) -> Unit,
)

/**
 * The full memory graph on one Canvas. Nodes sit where [MemoryGraphLiveLayout] puts them: they
 * bloom open, can be dragged (neighbours follow, then everything settles) and are drawn by
 * [drawGraph]. Pinch/drag on empty graph (or wheel/drag) moves the viewport; hovering a node with
 * a pointer focuses it; a tap selects the nearest node within a 48dp touch target.
 */
@Composable
internal fun MemoryGraphCanvas(
    params: MemoryGraphCanvasParams,
    viewportState: MemoryGraphViewportState,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    val density = LocalDensity.current
    // A docked inspector can leave a wide window's graph phone-narrow: label by the canvas's own width.
    val narrowCanvas = with(density) { viewportState.size.width.toDp() } < LettaDimens.Pane.wideBreakpoint
    val labels = rememberMemoryNodeLabels(params.view, palette, params.compact || narrowCanvas)
    val neighbours = remember(params.view) { params.view.adjacency() }
    val minTouchPx = MemoryGraphNodeMetrics.MIN_TOUCH_RADIUS * density.density
    var hoveredId by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(params)
    val hit = remember(viewportState) {
        { point: Offset -> current.nodeAt(point, viewportState.viewport, minTouchPx) }
    }
    LaunchedEffect(params.layout) { viewportState.sync(params.layout, viewportState.size) }
    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportState.sync(current.layout, MemoryGraphSize(it.width.toFloat(), it.height.toFloat())) }
            .graphGestures(viewportState, onHover = { point -> hoveredId = point?.let(hit) })
            .nodeDrag(viewportState, hit) { current.live }
            // Keyed on the viewport holder and reading [current]: a reload must not restart
            // (and drop) an in-flight tap. No onDoubleTap: it would hold every tap for the timeout.
            .pointerInput(viewportState) { detectTapGestures { offset -> current.onNodeTap(hit(offset)) } }
            .pointerHoverIcon(if (hoveredId != null) PointerIcon.Hand else PointerIcon.Default)
            .semantics { contentDescription = "Memory graph, ${params.view.nodes.size} nodes" },
    ) {
        val scene = GraphScene(params.view, neighbours, params.looks, labels, palette, params.selectedNodeId, hoveredId)
        drawGraph(GraphFrame(scene, params.live.layout, viewportState.viewport))
    }
}

/** The nearest node whose drawn radius (or the touch radius) covers [point], on the live layout. */
private fun MemoryGraphCanvasParams.nodeAt(point: Offset, viewport: MemoryGraphViewport, minTouchPx: Float): String? {
    val positions = live.layout
    var best: String? = null
    var bestDistance = Float.MAX_VALUE
    view.nodes.forEach { node ->
        val centre = positions[node.id]?.let(viewport::toScreen) ?: return@forEach
        val dx = point.x - centre.x
        val dy = point.y - centre.y
        val distance = sqrt(dx * dx + dy * dy)
        val reach = max((looks[node.id]?.radius ?: MemoryNodeLooks.SMALL_RADIUS) * viewport.scale, minTouchPx)
        if (distance <= reach && distance < bestDistance) {
            bestDistance = distance
            best = node.id
        }
    }
    return best
}

/**
 * A drag that starts on a node moves the node (pinned, so neighbours follow) instead of the
 * view. It consumes its moves, which cancels the pan detector and the tap for that gesture.
 */
private fun Modifier.nodeDrag(
    state: MemoryGraphViewportState,
    hit: (Offset) -> String?,
    live: () -> MemoryGraphLiveLayout,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val nodeId = hit(down.position) ?: return@awaitEachGesture
        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        live().drag(nodeId, state.viewport.toLayout(start.position.toPoint()))
        drag(start.id) { change ->
            live().drag(nodeId, state.viewport.toLayout(change.position.toPoint()))
            change.consume()
        }
        live().endDrag()
    }
}

/** Pinch/drag on touch, drag + wheel on desktop; pointer hover reports where it is (null on exit). */
private fun Modifier.graphGestures(state: MemoryGraphViewportState, onHover: (Offset?) -> Unit): Modifier =
    pointerInput(state) {
        detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
    }.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Scroll -> {
                        state.transform(change.position, Offset.Zero, exp(-change.scrollDelta.y * WHEEL_ZOOM_RATE))
                        change.consume()
                    }
                    PointerEventType.Move -> onHover(if (change.pressed) null else change.position)
                    PointerEventType.Exit -> onHover(null)
                    else -> Unit
                }
            }
        }
    }

private const val WHEEL_ZOOM_RATE = 0.12f
