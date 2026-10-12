package io.ak1.drawbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.arrowHeadSize
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.connectorLabelMaxWidth
import io.ak1.drawbox.domain.model.isConnector
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where each element of a drawing paints, kept up to date as the drawing changes, and a grid over
 * it so a frame finds the elements in a world rectangle without walking the whole board.
 *
 * Bounds are cached per element instance: the reducer makes a new instance for every change, so an
 * element whose instance is the same as last time has the same bounds and is not measured again.
 * Every element that changed (added, removed, or a new instance) since the last [takeDirty] is
 * remembered by where it painted before and after, so the cached static layer can tell whether a
 * change touches the part of the board it holds.
 *
 * Paint bounds are deliberately generous (stroke width, arrow heads, text that overflows its box):
 * an element culled by mistake is a missing element, one drawn needlessly costs nothing visible.
 */
internal class RenderIndex {
    private var elements: List<Element> = emptyList()
    private var boundsById = HashMap<String, Cached>()
    private val grid = HashMap<Long, MutableList<Int>>()
    private val oversized = ArrayList<Int>()
    private val dirty = ArrayList<Change>()

    private class Cached(val element: Element, val bounds: Rect)

    /** An element that changed, and an area it painted (before or after the change). */
    class Change(val id: String, val bounds: Rect)

    /** Brings the index up to [ordered], the drawing in paint order. Cheap when it has not changed. */
    fun update(ordered: List<Element>) {
        if (ordered === elements) return
        val next = HashMap<String, Cached>(ordered.size * 2)
        for (element in ordered) {
            val previous = boundsById[element.id]
            next[element.id] = if (previous != null && previous.element === element) {
                previous
            } else {
                previous?.let { dirty += Change(element.id, it.bounds) }
                Cached(element, paintBounds(element)).also { dirty += Change(element.id, it.bounds) }
            }
        }
        boundsById.forEach { (id, cached) -> if (id !in next) dirty += Change(id, cached.bounds) }
        boundsById = next
        elements = ordered
        rebuildGrid()
    }

    /** Where [element] paints, as last indexed (computed now if it is not in the index). */
    fun boundsOf(element: Element): Rect {
        val cached = boundsById[element.id]
        return if (cached != null && cached.element === element) cached.bounds else paintBounds(element)
    }

    /** The elements painting into [world], in paint order. */
    fun query(world: Rect): List<Element> {
        if (elements.isEmpty()) return emptyList()
        val (x0, y0) = cellOf(world.left, world.top)
        val (x1, y1) = cellOf(world.right, world.bottom)
        val cells = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
        // A view wider than the board's grid is cheaper to answer by walking the list.
        if (cells > elements.size) return elements.filter { boundsOf(it).overlaps(world) }
        val hits = HashSet<Int>()
        for (cx in x0..x1) for (cy in y0..y1) grid[key(cx, cy)]?.let(hits::addAll)
        hits.addAll(oversized)
        return hits.sorted().mapNotNull { i -> elements[i].takeIf { boundsOf(it).overlaps(world) } }
    }

    /** The areas changed since the last call, before and after each change; cleared by the call. */
    fun takeDirty(): List<Change> {
        if (dirty.isEmpty()) return emptyList()
        val taken = dirty.toList()
        dirty.clear()
        return taken
    }

    private fun rebuildGrid() {
        grid.clear()
        oversized.clear()
        elements.forEachIndexed { i, element ->
            val b = boundsById.getValue(element.id).bounds
            val (x0, y0) = cellOf(b.left, b.top)
            val (x1, y1) = cellOf(b.right, b.bottom)
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_CELLS_PER_ELEMENT) {
                oversized += i
            } else {
                for (cx in x0..x1) for (cy in y0..y1) grid.getOrPut(key(cx, cy)) { ArrayList(4) } += i
            }
        }
    }

    private fun cellOf(x: Float, y: Float): Pair<Int, Int> =
        cellCoord(x) to cellCoord(y)

    private fun cellCoord(v: Float): Int =
        floor((v / CELL).coerceIn(-CELL_LIMIT, CELL_LIMIT)).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    companion object {
        /** World units per grid cell. */
        const val CELL = 512f
        private const val CELL_LIMIT = 1_000_000f
        private const val MAX_CELLS_PER_ELEMENT = 64L

        /** Allowance for text that lays out past its box, in ems of its font size. */
        private const val TEXT_OVERFLOW_EMS = 8f

        /** Where [element] can paint: its bounds grown by what is drawn outside them, then rotated. */
        fun paintBounds(element: Element): Rect {
            val raw = when (element) {
                is Element.Path -> pathBounds(element)
                else -> element.bounds()
            }
            val padded = raw.inflate(padOf(element))
            return if (element.rotation == 0f) padded else rotatedAabb(padded, raw.center, element.rotation)
        }

        private fun pathBounds(path: Element.Path): Rect {
            val samples = path.samples
            if (samples.isEmpty()) return Rect.Zero
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var width = path.strokeWidth
            for (s in samples) {
                val p = s.position
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
                width = max(width, s.width)
            }
            return Rect(minX, minY, maxX, maxY).inflate(width)
        }

        private fun padOf(element: Element): Float = when (element) {
            is Element.Path -> PAD
            is Element.Image -> PAD
            is Element.Text -> PAD + element.fontSize * TEXT_OVERFLOW_EMS
            is Element.Shape -> {
                val stroke = element.strokeWidth + PAD
                val head = if (element.shapeType == ShapeType.ARROW) element.arrowHeadSize() else 0f
                val text = when {
                    element.text.isEmpty() -> 0f
                    element.isConnector -> element.connectorLabelMaxWidth() + element.fontSize * 2f
                    else -> element.fontSize * TEXT_OVERFLOW_EMS
                }
                stroke + max(head, text)
            }
        }

        private const val PAD = 4f

        private fun rotatedAabb(rect: Rect, pivot: Offset, degrees: Float): Rect {
            val rad = degrees * (kotlin.math.PI / 180.0).toFloat()
            val c = cos(rad)
            val s = sin(rad)
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (corner in listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)) {
                val dx = corner.x - pivot.x
                val dy = corner.y - pivot.y
                val x = pivot.x + dx * c - dy * s
                val y = pivot.y + dx * s + dy * c
                minX = min(minX, x); minY = min(minY, y)
                maxX = max(maxX, x); maxY = max(maxY, y)
            }
            return Rect(minX, minY, maxX, maxY)
        }
    }
}
