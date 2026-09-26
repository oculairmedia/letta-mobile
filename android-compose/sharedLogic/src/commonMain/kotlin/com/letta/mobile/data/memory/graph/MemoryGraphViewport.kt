package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Maps layout space to screen pixels: `screen = layout * scale + offset`.
 * [scale] folds in display density, so one layout unit is one dp at scale ==
 * density. Pure math, shared by every platform's gesture handling.
 */
@Immutable
data class MemoryGraphViewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun toScreen(point: MemoryGraphPoint): MemoryGraphPoint =
        MemoryGraphPoint(point.x * scale + offsetX, point.y * scale + offsetY)

    fun toLayout(screen: MemoryGraphPoint): MemoryGraphPoint =
        MemoryGraphPoint((screen.x - offsetX) / scale, (screen.y - offsetY) / scale)

    fun panBy(dx: Float, dy: Float): MemoryGraphViewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** Zoom by [factor] keeping the layout point under [focus] (screen px) fixed. */
    fun zoomAbout(focus: MemoryGraphPoint, factor: Float, limits: MemoryGraphZoomLimits): MemoryGraphViewport {
        val nextScale = (scale * factor).coerceIn(limits.minScale, limits.maxScale)
        val anchor = toLayout(focus)
        return MemoryGraphViewport(
            scale = nextScale,
            offsetX = focus.x - anchor.x * nextScale,
            offsetY = focus.y - anchor.y * nextScale,
        )
    }

    /** Keep [scale], move so [point] lands at [screenCenter]. */
    fun centeredOn(point: MemoryGraphPoint, screenCenter: MemoryGraphPoint): MemoryGraphViewport =
        copy(offsetX = screenCenter.x - point.x * scale, offsetY = screenCenter.y - point.y * scale)

    companion object {
        /** Fit [bounds] inside a [size] (px) screen, leaving [padding] (px) on each side. */
        fun fit(bounds: MemoryGraphBounds, size: MemoryGraphSize, padding: Float, limits: MemoryGraphZoomLimits): MemoryGraphViewport {
            if (size.width <= 0f || size.height <= 0f) return MemoryGraphViewport()
            val usableWidth = max(size.width - 2f * padding, 1f)
            val usableHeight = max(size.height - 2f * padding, 1f)
            val fitScale = min(usableWidth / max(bounds.width, 1f), usableHeight / max(bounds.height, 1f))
            val scale = fitScale.coerceIn(limits.minScale, limits.fitMaxScale)
            return MemoryGraphViewport(scale = scale)
                .centeredOn(bounds.center, MemoryGraphPoint(size.width / 2f, size.height / 2f))
        }
    }
}

@Immutable
data class MemoryGraphSize(val width: Float, val height: Float)

/**
 * Scale limits in px-per-layout-unit. Build with [forDensity] so a fit never
 * blows a tiny graph up past [fitMaxScale] (nodes stay node-sized).
 */
@Immutable
data class MemoryGraphZoomLimits(
    val minScale: Float,
    val maxScale: Float,
    val fitMaxScale: Float,
) {
    companion object {
        fun forDensity(density: Float): MemoryGraphZoomLimits = MemoryGraphZoomLimits(
            minScale = density * MIN_ZOOM,
            maxScale = density * MAX_ZOOM,
            fitMaxScale = density * FIT_MAX_ZOOM,
        )

        private const val MIN_ZOOM = 0.15f
        private const val MAX_ZOOM = 4f
        private const val FIT_MAX_ZOOM = 1.4f
    }
}

/** Node sizing shared by drawing and hit testing (layout units ≈ dp). */
object MemoryGraphNodeMetrics {
    /** Desktop sizing: diameter `16 + 4·degree`, clamped to 14..40. */
    fun radius(degree: Int): Float = (BASE_DIAMETER + degree * DIAMETER_PER_LINK).coerceIn(MIN_DIAMETER, MAX_DIAMETER) / 2f

    /** Half of a 48dp touch target: taps within this of a centre always hit. */
    const val MIN_TOUCH_RADIUS: Float = 24f

    private const val BASE_DIAMETER = 16f
    private const val DIAMETER_PER_LINK = 4f
    private const val MIN_DIAMETER = 14f
    private const val MAX_DIAMETER = 40f
}

/**
 * Screen-space hit test: the nearest node whose centre is within
 * max(drawn radius, touch radius) of the tap. Small, zoomed-out nodes stay
 * tappable on a phone; overlapping targets resolve to the closest centre.
 */
object MemoryGraphHitTest {
    fun nodeAt(
        tap: MemoryGraphPoint,
        target: MemoryGraphHitTarget,
    ): String? {
        var bestId: String? = null
        var bestDistance = Float.MAX_VALUE
        target.view.nodes.forEach { node ->
            val centre = target.layout[node.id]?.let(target.viewport::toScreen) ?: return@forEach
            val distance = distance(tap, centre)
            val reach = max(MemoryGraphNodeMetrics.radius(target.view.degreeOf(node.id)) * target.viewport.scale, target.minTouchRadiusPx)
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance
                bestId = node.id
            }
        }
        return bestId
    }

    private fun distance(a: MemoryGraphPoint, b: MemoryGraphPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** Everything a hit test reads, bundled so the call site stays small. */
@Immutable
data class MemoryGraphHitTarget(
    val view: MemoryGraphView,
    val layout: MemoryGraphLayout,
    val viewport: MemoryGraphViewport,
    val minTouchRadiusPx: Float,
)
