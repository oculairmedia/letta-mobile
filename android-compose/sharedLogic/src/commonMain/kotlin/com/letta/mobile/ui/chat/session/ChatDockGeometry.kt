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
 * Collapsed, [anchorY] places the bar itself in the container's free height, so the bar moves
 * anywhere on the canvas, the top included; [ChatDockGeometryMath.collapse] and
 * [ChatDockGeometryMath.expand] convert it so the bar stays where the open panel's bar was.
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

/** How far a drag moved, in dp. */
@Immutable
data class ChatDockDelta(val dx: Float, val dy: Float)

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
     * Where the panel is drawn in [frame]. Collapsed, it is the composer bar alone, as wide as the
     * expanded panel and placed by its own anchor in the free height (see [ChatDockGeometry]).
     */
    fun rect(geometry: ChatDockGeometry, frame: ChatDockFrame): ChatDockRect {
        val area = Area(frame)
        val expanded = expandedRect(geometry, area)
        if (!geometry.collapsed) return expanded
        val height = barHeight(frame, area)
        val top = area.top + sanitize(geometry).anchorY * (area.height - height).coerceAtLeast(0f)
        return expanded.copy(top = top, height = height)
    }

    /**
     * Moves the panel by ([dx], [dy]) dp, stopping at the container edges. A collapsed bar stops
     * at its own edges, not the expanded panel's: minimised it goes anywhere on the canvas.
     */
    fun drag(geometry: ChatDockGeometry, dx: Float, dy: Float, frame: ChatDockFrame): ChatDockGeometry {
        val area = Area(frame)
        val current = rect(geometry, frame)
        return geometry.copy(
            anchorX = area.horizontal.anchorFor(current.left + dx, current.width, geometry.anchorX),
            anchorY = area.vertical.anchorFor(current.top + dy, current.height, geometry.anchorY),
        )
    }

    /**
     * Moves [edge] by [delta] dp. The opposite edge stays put; the size stays within the
     * limits and the container. A collapsed panel only resizes its width.
     */
    fun resize(geometry: ChatDockGeometry, edge: ChatDockEdge, delta: ChatDockDelta, frame: ChatDockFrame): ChatDockGeometry {
        val area = Area(frame)
        val current = rect(geometry, frame)
        var next = geometry
        if (edge.horizontal) {
            val (left, width) = area.horizontal.resize(Span(current.left, current.right, edge.left, edge.right), delta.dx)
            next = next.copy(widthDp = width, anchorX = area.horizontal.anchorFor(left, width, geometry.anchorX))
        }
        if (edge.vertical && !geometry.collapsed) {
            val (top, height) = area.vertical.resize(Span(current.top, current.bottom, edge.top, edge.bottom), delta.dy)
            next = next.copy(heightDp = height, anchorY = area.vertical.anchorFor(top, height, geometry.anchorY))
        }
        return next
    }

    /**
     * Shows only the composer bar, where the open panel's bar was in [frame]; the expanded size
     * is kept. Without a frame (nothing laid out yet) only the flag changes.
     */
    fun collapse(geometry: ChatDockGeometry, frame: ChatDockFrame? = null): ChatDockGeometry {
        if (geometry.collapsed) return geometry
        return switchCollapsed(geometry, collapsed = true, frame = frame)
    }

    /**
     * Opens the panel again at its last expanded size, its composer bar where the collapsed bar
     * is in [frame]. Where there is no room above the bar for the panel, the panel stops at the
     * top and grows downward instead: it never opens off the canvas. Without a frame only the
     * flag changes.
     */
    fun expand(geometry: ChatDockGeometry, frame: ChatDockFrame? = null): ChatDockGeometry {
        if (!geometry.collapsed) return geometry
        return switchCollapsed(geometry, collapsed = false, frame = frame)
    }

    /**
     * Flips [ChatDockGeometry.collapsed] to [collapsed], keeping the composer bar's bottom edge
     * where it is in [frame]: the bar is the bottom of the open panel and the whole collapsed one.
     */
    private fun switchCollapsed(geometry: ChatDockGeometry, collapsed: Boolean, frame: ChatDockFrame?): ChatDockGeometry {
        val target = geometry.copy(collapsed = collapsed)
        if (frame == null) return target
        val barBottom = rect(geometry, frame).bottom
        val height = rect(target, frame).height
        return target.copy(anchorY = Area(frame).vertical.anchorFor(barBottom - height, height, geometry.anchorY))
    }

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

    private fun barHeight(frame: ChatDockFrame, area: Area): Float = frame.collapsedHeightDp.coerceIn(0f, area.height)

    private class Span(val start: Float, val end: Float, val movesStart: Boolean, val movesEnd: Boolean)

    /** One axis of the usable area: where it starts, how long it is, and the panel size limits along it. */
    private class AreaAxis(val origin: Float, val length: Float, val minSize: Float, val maxSize: Float) {
        private val max: Float get() = origin + length

        /** The anchor fraction that puts a [size]-long panel's start at [start] on this axis. */
        fun anchorFor(start: Float, size: Float, fallback: Float): Float {
            val free = length - size
            if (free <= 0f) return fallback.fractionOr(Default.anchorX)
            return ((start - origin) / free).coerceIn(0f, 1f)
        }

        /** Moves [span]'s moving ends by [delta] within this axis; returns the new start and size. */
        fun resize(span: Span, delta: Float): Pair<Float, Float> {
            var start = span.start
            var end = span.end
            if (span.movesEnd) {
                end = (end + delta).coerceIn(start + minSize, minOf(start + maxSize, max))
            }
            if (span.movesStart) {
                start = (start + delta).coerceIn(maxOf(end - maxSize, origin), end - minSize)
            }
            return start to (end - start)
        }
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
        val maxWidth: Float = minOf(limits.maxWidthDp, width)
        val maxHeight: Float = minOf(limits.maxHeightDp, height)
        val minWidth: Float = minOf(limits.minWidthDp, maxWidth)
        val minHeight: Float = minOf(limits.minHeightDp, maxHeight)
        val horizontal: AreaAxis = AreaAxis(left, width, minWidth, maxWidth)
        val vertical: AreaAxis = AreaAxis(top, height, minHeight, maxHeight)
    }

    private fun Float.fractionOr(fallback: Float): Float = if (isFinite()) coerceIn(0f, 1f) else fallback
}
