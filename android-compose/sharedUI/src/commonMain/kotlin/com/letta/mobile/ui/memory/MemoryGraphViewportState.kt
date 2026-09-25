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
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphSize
import com.letta.mobile.data.memory.graph.MemoryGraphViewport
import com.letta.mobile.data.memory.graph.MemoryGraphZoomLimits

/**
 * Pan/zoom state for the memory graph canvas. The math lives in sharedLogic
 * ([MemoryGraphViewport]); this holder only makes it observable and decides
 * when to auto-fit (until the user moves the view, and again on a new layout).
 */
@Stable
class MemoryGraphViewportState internal constructor(
    val limits: MemoryGraphZoomLimits,
    private val fitPaddingPx: Float,
) {
    var viewport: MemoryGraphViewport by mutableStateOf(MemoryGraphViewport(scale = limits.fitMaxScale))
        private set

    var size: MemoryGraphSize by mutableStateOf(MemoryGraphSize(0f, 0f))
        private set

    private var userMoved = false
    private var fittedLayout: MemoryGraphLayout? = null

    /** Refit when the layout or the canvas size changes, unless the user has taken over. */
    fun sync(layout: MemoryGraphLayout, canvasSize: MemoryGraphSize) {
        val newLayout = layout !== fittedLayout
        val resized = canvasSize != size
        size = canvasSize
        if (newLayout) userMoved = false
        if ((newLayout || resized) && !userMoved) fit(layout)
    }

    fun fit(layout: MemoryGraphLayout) {
        fittedLayout = layout
        userMoved = false
        if (layout.isEmpty) return
        viewport = MemoryGraphViewport.fit(layout.bounds, size, fitPaddingPx, limits)
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
     * Bring [point] (layout space) to [screenTarget]. Animated unless
     * [reducedMotion]; either way it counts as a user move so no refit fights it.
     */
    suspend fun reveal(point: MemoryGraphPoint, screenTarget: MemoryGraphPoint, reducedMotion: Boolean) {
        userMoved = true
        val start = viewport
        val end = start.centeredOn(point, screenTarget)
        if (reducedMotion) {
            viewport = end
            return
        }
        animate(0f, 1f) { fraction, _ ->
            viewport = start.copy(
                offsetX = start.offsetX + (end.offsetX - start.offsetX) * fraction,
                offsetY = start.offsetY + (end.offsetY - start.offsetY) * fraction,
            )
        }
    }
}

internal fun Offset.toPoint(): MemoryGraphPoint = MemoryGraphPoint(x, y)

@Composable
fun rememberMemoryGraphViewportState(): MemoryGraphViewportState {
    val density = LocalDensity.current
    return remember(density.density) {
        MemoryGraphViewportState(
            limits = MemoryGraphZoomLimits.forDensity(density.density),
            fitPaddingPx = FIT_PADDING_LAYOUT_UNITS * density.density,
        )
    }
}

/** Room around a fitted graph for edge labels and the filter overlay. */
private const val FIT_PADDING_LAYOUT_UNITS = 48f
