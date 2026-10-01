package com.letta.mobile.ui.chat.session

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bglj6.1: where the docked chat panel sits over the canvas, and how big it is.
 *
 * Position is relative so it survives window resizes: [anchorX] / [anchorY] place the panel
 * within the free space of its container (0 = against the left / top margin, 1 = against the
 * right / bottom margin, 0.5 = centred). Size is in dp; a null size means "the default for this
 * container" (see [ChatDockLimits]), so a fresh panel fits a phone and a wide window alike.
 * [collapsed] shows only the composer bar; the expanded size is kept for when it opens again.
 *
 * The host owns and persists it; [ChatDockGeometryMath] derives every change from it.
 */
@Immutable
@Serializable
data class ChatDockGeometry(
    @SerialName("anchor_x") val anchorX: Float = 0.5f,
    @SerialName("anchor_y") val anchorY: Float = 1f,
    @SerialName("width_dp") val widthDp: Float? = null,
    @SerialName("height_dp") val heightDp: Float? = null,
    @SerialName("collapsed") val collapsed: Boolean = false,
) {
    companion object {
        /** Bottom-centre, default width and height, expanded. */
        val Default = ChatDockGeometry()
    }
}

/** The panel's size rules, in dp. The UI supplies them from its tokens. */
@Immutable
data class ChatDockLimits(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val maxWidthDp: Float,
    val maxHeightDp: Float,
    /** Space kept between the panel and the container edges. */
    val marginDp: Float,
    val defaultWidthDp: Float,
    /** The default expanded height as a share of the container height. */
    val defaultHeightFraction: Float,
    /**
     * Extra space kept above the panel, on top of [marginDp]: what the panel draws above its own
     * top edge (the mascot badge) stays inside the container.
     */
    val topInsetDp: Float = 0f,
)

/** A resolved panel rectangle in dp, relative to the container's top-left. */
@Immutable
data class ChatDockRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * What the geometry is resolved against: the container size in dp, the size rules, and the
 * collapsed composer bar's measured height in dp.
 */
@Immutable
data class ChatDockFrame(
    val containerWidthDp: Float,
    val containerHeightDp: Float,
    val limits: ChatDockLimits,
    val collapsedHeightDp: Float,
)

/** Which panel edges a resize moves; corners move two. */
enum class ChatDockEdge(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    Left(true, false, false, false),
    Top(false, true, false, false),
    Right(false, false, true, false),
    Bottom(false, false, false, true),
    TopLeft(true, true, false, false),
    TopRight(false, true, true, false),
    BottomLeft(true, false, false, true),
    BottomRight(false, false, true, true),
    ;

    val horizontal: Boolean get() = left || right
    val vertical: Boolean get() = top || bottom
}

/**
 * Pure geometry for the docked panel. Every function takes the container size in dp; the
 * resolved rectangle always lies inside the container (less [ChatDockLimits.marginDp]), so a
 * window that shrinks clamps the panel without rewriting what the person chose.
 */
object ChatDockGeometryMath {
    /** Repairs a value read from disk: fractions into 0..1, non-finite or non-positive sizes to default. */
    fun sanitize(geometry: ChatDockGeometry): ChatDockGeometry = geometry.copy(
        anchorX = geometry.anchorX.fractionOr(Default.anchorX),
        anchorY = geometry.anchorY.fractionOr(Default.anchorY),
        widthDp = geometry.widthDp?.takeIf { it.isFinite() && it > 0f },
        heightDp = geometry.heightDp?.takeIf { it.isFinite() && it > 0f },
    )

    /**
     * Where the panel is drawn in [frame]. Collapsed, it is the expanded panel's composer bar: the
     * bottom of the expanded rectangle, so opening it again grows the panel upward in place.
     */
    fun rect(geometry: ChatDockGeometry, frame: ChatDockFrame): ChatDockRect {
        val area = Area(frame)
        val expanded = expandedRect(geometry, area)
        if (!geometry.collapsed) return expanded
        val height = frame.collapsedHeightDp.coerceIn(0f, area.height)
        val top = (expanded.bottom - height).coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
        return expanded.copy(top = top, height = height)
    }

    /**
     * Moves the panel by ([dx], [dy]) dp, stopping at the container edges. A collapsed bar moves
     * its expanded panel, so the anchor stays the expanded panel's and expanding never jumps.
     */
    fun drag(geometry: ChatDockGeometry, dx: Float, dy: Float, frame: ChatDockFrame): ChatDockGeometry {
        val area = Area(frame)
        val current = expandedRect(geometry, area)
        return geometry.copy(
            anchorX = anchorFor(current.left + dx, current.width, area.left, area.width, geometry.anchorX),
            anchorY = anchorFor(current.top + dy, current.height, area.top, area.height, geometry.anchorY),
        )
    }

    /**
     * Moves [edge] by ([dx], [dy]) dp. The opposite edge stays put; the size stays within the
     * limits and the container. A collapsed panel only resizes its width.
     */
    fun resize(geometry: ChatDockGeometry, edge: ChatDockEdge, dx: Float, dy: Float, frame: ChatDockFrame): ChatDockGeometry {
        val area = Area(frame)
        val current = rect(geometry, frame)
        var next = geometry
        if (edge.horizontal) {
            val (left, right) = resizeSpan(
                Span(current.left, current.right, edge.left, edge.right),
                dx,
                SpanBounds(area.left, area.right, area.minWidth, area.maxWidth),
            )
            val width = right - left
            next = next.copy(widthDp = width, anchorX = anchorFor(left, width, area.left, area.width, geometry.anchorX))
        }
        if (edge.vertical && !geometry.collapsed) {
            val (top, bottom) = resizeSpan(
                Span(current.top, current.bottom, edge.top, edge.bottom),
                dy,
                SpanBounds(area.top, area.bottom, area.minHeight, area.maxHeight),
            )
            val height = bottom - top
            next = next.copy(heightDp = height, anchorY = anchorFor(top, height, area.top, area.height, geometry.anchorY))
        }
        return next
    }

    /** Shows only the composer bar; the expanded size and the anchor are kept. */
    fun collapse(geometry: ChatDockGeometry): ChatDockGeometry = geometry.copy(collapsed = true)

    /** Opens the panel again at its last expanded size. */
    fun expand(geometry: ChatDockGeometry): ChatDockGeometry = geometry.copy(collapsed = false)

    /** Back to the default placement: bottom-centre, default size, expanded. */
    fun reset(): ChatDockGeometry = Default

    private val Default = ChatDockGeometry.Default

    private fun expandedRect(geometry: ChatDockGeometry, area: Area): ChatDockRect {
        val width = expandedWidth(geometry, area)
        val height = expandedHeight(geometry, area)
        val clean = sanitize(geometry)
        return ChatDockRect(
            left = area.left + clean.anchorX * (area.width - width).coerceAtLeast(0f),
            top = area.top + clean.anchorY * (area.height - height).coerceAtLeast(0f),
            width = width,
            height = height,
        )
    }

    private fun expandedWidth(geometry: ChatDockGeometry, area: Area): Float =
        (sanitize(geometry).widthDp ?: area.limits.defaultWidthDp).coerceIn(area.minWidth, area.maxWidth)

    private fun expandedHeight(geometry: ChatDockGeometry, area: Area): Float =
        (sanitize(geometry).heightDp ?: (area.containerHeight * area.limits.defaultHeightFraction))
            .coerceIn(area.minHeight, area.maxHeight)

    /** The anchor fraction that puts a [size]-long panel's start at [start] in a [span] from [origin]. */
    private fun anchorFor(start: Float, size: Float, origin: Float, span: Float, fallback: Float): Float {
        val free = span - size
        if (free <= 0f) return fallback.fractionOr(Default.anchorX)
        return ((start - origin) / free).coerceIn(0f, 1f)
    }

    private class Span(val start: Float, val end: Float, val movesStart: Boolean, val movesEnd: Boolean)

    private class SpanBounds(val min: Float, val max: Float, val minSize: Float, val maxSize: Float)

    private fun resizeSpan(span: Span, delta: Float, bounds: SpanBounds): Pair<Float, Float> {
        var start = span.start
        var end = span.end
        if (span.movesEnd) {
            end = (end + delta).coerceIn(start + bounds.minSize, minOf(start + bounds.maxSize, bounds.max))
        }
        if (span.movesStart) {
            start = (start + delta).coerceIn(maxOf(end - bounds.maxSize, bounds.min), end - bounds.minSize)
        }
        return start to end
    }

    /** The usable area: the container less its margin (and top inset), and the size limits that fit in it. */
    private class Area(frame: ChatDockFrame) {
        val limits: ChatDockLimits = frame.limits
        val containerWidth: Float = frame.containerWidthDp
        val containerHeight: Float = frame.containerHeightDp
        val left: Float = limits.marginDp.coerceAtMost(containerWidth / 2f)
        private val bottomMargin: Float = limits.marginDp.coerceAtMost(containerHeight / 2f)
        val top: Float = (limits.marginDp + limits.topInsetDp.coerceAtLeast(0f)).coerceAtMost(containerHeight / 2f)
        val width: Float = (containerWidth - 2f * left).coerceAtLeast(0f)
        val height: Float = (containerHeight - top - bottomMargin).coerceAtLeast(0f)
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val maxWidth: Float = minOf(limits.maxWidthDp, width)
        val maxHeight: Float = minOf(limits.maxHeightDp, height)
        val minWidth: Float = minOf(limits.minWidthDp, maxWidth)
        val minHeight: Float = minOf(limits.minHeightDp, maxHeight)
    }

    private fun Float.fractionOr(fallback: Float): Float = if (isFinite()) coerceIn(0f, 1f) else fallback
}
