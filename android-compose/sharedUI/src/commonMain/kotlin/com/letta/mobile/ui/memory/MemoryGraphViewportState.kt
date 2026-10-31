package com.letta.mobile.ui.memory

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import com.letta.mobile.data.memory.graph.MemoryGraphBounds
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphSize
import com.letta.mobile.data.memory.graph.MemoryGraphViewport
import com.letta.mobile.data.memory.graph.MemoryGraphZoomLimits
import com.letta.mobile.ui.theme.graph.GraphDimens

/**
 * Pan/zoom state for the memory graph canvas. The math lives in sharedLogic
 * ([MemoryGraphViewport]); this holder only makes it observable and decides
 * when to auto-fit (until the user moves the view, and again on a new layout).
 */
@Stable
class MemoryGraphViewportState internal constructor(
    val limits: MemoryGraphZoomLimits,
    private val fitPaddingPx: Float,
    private val compactFitPaddingPx: Float,
) {
    var viewport: MemoryGraphViewport by mutableStateOf(MemoryGraphViewport(scale = limits.fitMaxScale))
        private set

    var size: MemoryGraphSize by mutableStateOf(MemoryGraphSize(0f, 0f))
        private set

    /**
     * Canvas edges covered by chrome (a phone's bottom sheet, the corner fit button): framing
     * centres the graph in what is left, so no node is placed under them.
     */
    internal var insets: MemoryGraphInsets = MemoryGraphInsets.None

    /** A phone's canvas: labels are clamped inside it, so framing needs less room around nodes. */
    internal var compact: Boolean = false

    private var userMoved = false
    private var fittedLayout: MemoryGraphLayout? = null

    /** Refit when the layout or the canvas size changes, unless the user has taken over. */
    fun sync(layout: MemoryGraphLayout, canvasSize: MemoryGraphSize) {
        val newLayout = layout !== fittedLayout
        val resized = canvasSize != size
        size = canvasSize
        if (newLayout) userMoved = false
        if (shouldRefit(newLayout, resized)) fit(layout)
    }

    /** Something changed under the view and the user has not taken over panning. */
    private fun shouldRefit(newLayout: Boolean, resized: Boolean): Boolean {
        val changed = newLayout || resized
        return changed && !userMoved
    }

    fun fit(layout: MemoryGraphLayout) {
        fittedLayout = layout
        userMoved = false
        if (layout.isEmpty) return
        viewport = framing(layout.bounds)
    }

    /** The viewport that fits [bounds] (layout space) in the canvas minus [insets]. */
    private fun framing(bounds: MemoryGraphBounds): MemoryGraphViewport {
        val open = MemoryGraphSize(
            (size.width - insets.left - insets.right).coerceAtLeast(1f),
            (size.height - insets.top - insets.bottom).coerceAtLeast(1f),
        )
        val padding = if (compact) compactFitPaddingPx else fitPaddingPx
        return MemoryGraphViewport.fit(bounds, open, padding, limits).panBy(insets.left, insets.top)
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        userMoved = true
        viewport = viewport.zoomAbout(centroid.toPoint(), zoom, limits).panBy(pan.x, pan.y)
    }

    fun zoomBy(factor: Float) {
        userMoved = true
        viewport = viewport.zoomAbout(MemoryGraphPoint(size.width / 2f, size.height / 2f), factor, limits)
    }

    /**
     * Frame [bounds] (layout space: a selected node and its neighbours, or the whole graph) in the
     * uncovered part of the canvas. Animated unless [reducedMotion]; either way it counts as a
     * user move so no refit fights it.
     */
    internal suspend fun frame(bounds: MemoryGraphBounds, reducedMotion: Boolean) {
        userMoved = true
        val start = viewport
        val end = framing(bounds)
        if (reducedMotion) {
            viewport = end
            return
        }
        animate(0f, 1f) { fraction, _ -> viewport = lerp(start, end, fraction) }
    }
}

private fun lerp(start: MemoryGraphViewport, end: MemoryGraphViewport, fraction: Float) = MemoryGraphViewport(
    scale = start.scale + (end.scale - start.scale) * fraction,
    offsetX = start.offsetX + (end.offsetX - start.offsetX) * fraction,
    offsetY = start.offsetY + (end.offsetY - start.offsetY) * fraction,
)

/** Canvas edges (px) the graph must not be framed under. */
internal data class MemoryGraphInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    companion object {
        val None = MemoryGraphInsets()
    }
}

internal fun Offset.toPoint(): MemoryGraphPoint = MemoryGraphPoint(x, y)

@Composable
fun rememberMemoryGraphViewportState(): MemoryGraphViewportState {
    val density = LocalDensity.current
    return remember(density.density) {
        MemoryGraphViewportState(
            limits = MemoryGraphZoomLimits.forDensity(density.density),
            fitPaddingPx = with(density) { GraphDimens.fitPadding.toPx() },
            compactFitPaddingPx = with(density) { GraphDimens.fitPaddingCompact.toPx() },
        )
    }
}
