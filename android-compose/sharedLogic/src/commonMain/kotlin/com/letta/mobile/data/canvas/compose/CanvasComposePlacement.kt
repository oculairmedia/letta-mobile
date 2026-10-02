package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasSceneDocument
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

/** A world-unit rectangle placement hands out. */
data class Slot(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    /** True when the two overlap with positive area; touching edges do not count. */
    fun intersects(other: Slot): Boolean = x < other.right && other.x < right && y < other.bottom && other.y < bottom

    /** True when the two overlap with positive area. */
    fun intersects(other: ComposeBounds): Boolean = intersects(Slot(other.x, other.y, other.width, other.height))

    fun toBounds(): ComposeBounds = ComposeBounds(x, y, width, height)
}

/** One item to place: a leaf at a fixed size, or a GROUP laid out around its children. */
sealed interface SizedItem {
    val key: String

    /** A note, checklist, card or text: its kind's width and its RESERVED height. */
    data class Leaf(override val key: String, val width: Float, val height: Float) : SizedItem

    /** A labelled frame whose children flow inside it; it fills one slot of the outer grid. */
    data class Group(override val key: String, val label: String?, val children: List<Leaf>) : SizedItem
}

/**
 * Where everything went. [slots] holds every key in request order (a group's slot is its enclosing
 * rectangle, its children follow it), [labels] the text box of each group's label, [bounds] the
 * rectangle around all of it (null for nothing placed).
 */
data class Placement(
    val slots: Map<String, Slot>,
    val labels: Map<String, Slot>,
    val bounds: ComposeBounds?,
)

/**
 * Deterministic placement for canvas_compose (letta-mobile-bglj6.9, plan section 3.4, D3).
 *
 * A pure function of the items and the board's content bounds: no viewport (the host has none), no
 * font, density or Compose API, so every peer and both hosts produce the same frames. The artifact
 * goes at (80, 80) on an empty board; otherwise RIGHT of the content, 48 clear and top-aligned,
 * unless the content is already wider than 2 400, in which case BELOW it, 48 clear and
 * left-aligned. Inside it, a row-major grid in request order: 1 column for 1 item, 2 for 2-4,
 * 3 for 5-9, 4 for 10 or more; the column is as wide as the widest item of that grid, rows are as
 * tall as their tallest item, 24 apart. A GROUP is a frame around its children's grid (same rule)
 * with 24 of padding and a label row of 32 (more if the label wraps) at the top, and takes one
 * cell of the outer grid.
 *
 * Every coordinate is a whole number when the sizes are (the reserve hands out whole numbers), so
 * the frames are exact in Float on every target.
 */
object CanvasComposePlacement {
    const val ORIGIN = 80f
    const val ARTIFACT_GAP = 48f
    const val WIDE_CONTENT = 2_400f
    const val GAP = 24f
    const val GROUP_PADDING = 24f
    const val GROUP_LABEL_ROW = 32f

    /** Where a group's label sits below the frame's top edge (inside its padding). */
    const val GROUP_LABEL_TOP = 16f

    /** A group label's font size (the compiler writes a DrawBox Text of this size). */
    const val GROUP_LABEL_FONT = 18f

    /** A legacy frameless document's width. */
    const val FRAMELESS_WIDTH = 320f

    /** Columns of a grid of [count] items. */
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count <= 4 -> 2
        count <= 9 -> 3
        else -> 4
    }

    /** Top-left of an artifact on a board whose content is [contentBounds] (null when empty). */
    fun origin(contentBounds: ComposeBounds?): Pair<Float, Float> = when {
        contentBounds == null -> ORIGIN to ORIGIN
        contentBounds.width > WIDE_CONTENT -> contentBounds.x to contentBounds.y + contentBounds.height + ARTIFACT_GAP
        else -> contentBounds.x + contentBounds.width + ARTIFACT_GAP to contentBounds.y
    }

    fun place(items: List<SizedItem>, contentBounds: ComposeBounds?): Placement {
        if (items.isEmpty()) return Placement(emptyMap(), emptyMap(), null)
        val slots = LinkedHashMap<String, Slot>()
        val labels = LinkedHashMap<String, Slot>()
        val sizes = items.map { sizeOf(it) }
        val cells = grid(sizes, origin(contentBounds))
        items.forEachIndexed { i, item ->
            val cell = cells[i]
            when (item) {
                is SizedItem.Leaf -> slots[item.key] = cell
                is SizedItem.Group -> placeGroup(item, cell, slots, labels)
            }
        }
        return Placement(slots, labels, union(cells))
    }

    /**
     * Frames for the documents that have none (legacy, written before compose), keyed by id: the
     * same flow as an artifact, in document-id order, each [FRAMELESS_WIDTH] wide and as tall as
     * the reserve books for its content. Framed documents are left alone; [contentBounds] is the
     * board without the frameless ones ([contentBounds] of the scene). Replaces the renderer's
     * staggered `defaultNoteFrame(index)`, so two frameless notes never overlap.
     */
    fun placeFrameless(documents: List<CanvasSceneDocument>, contentBounds: ComposeBounds?): Map<String, Slot> {
        val frameless = documents.filter { it.frame == null }.sortedBy { it.id }
        val items = frameless.map { document ->
            val scale = document.style?.fontScale ?: 1f
            SizedItem.Leaf(document.id, FRAMELESS_WIDTH, CanvasComposeReserve.reserveDocument(document.json, FRAMELESS_WIDTH, scale))
        }
        return place(items, contentBounds).slots
    }

    /** The height of a group's label row: 32, or the label's wrapped lines when they need more. */
    fun labelRow(label: String?, innerWidth: Float): Float {
        if (label.isNullOrBlank()) return GROUP_LABEL_ROW
        val font = GROUP_LABEL_FONT.toDouble()
        val lines = WorstCaseWrap(innerWidth.toDouble(), font).lines(label)
        return maxOf(GROUP_LABEL_ROW, ceil(lines * CanvasComposeReserve.LINE_HEIGHT_EM * font).toFloat())
    }

    private data class Size(val width: Float, val height: Float)

    private fun sizeOf(item: SizedItem): Size = when (item) {
        is SizedItem.Leaf -> Size(item.width, item.height)
        is SizedItem.Group -> {
            val inner = gridSize(item.children.map { Size(it.width, it.height) })
            val innerWidth = maxOf(inner.width, CanvasComposeContract.NOTE_WIDTH)
            Size(
                width = innerWidth + 2 * GROUP_PADDING,
                height = GROUP_PADDING + labelRow(item.label, innerWidth) + inner.height + GROUP_PADDING,
            )
        }
    }

    private fun placeGroup(group: SizedItem.Group, frame: Slot, slots: MutableMap<String, Slot>, labels: MutableMap<String, Slot>) {
        slots[group.key] = frame
        val innerWidth = frame.width - 2 * GROUP_PADDING
        val row = labelRow(group.label, innerWidth)
        if (!group.label.isNullOrBlank()) {
            labels[group.key] = Slot(frame.x + GROUP_PADDING, frame.y + GROUP_LABEL_TOP, innerWidth, row)
        }
        val cells = grid(group.children.map { Size(it.width, it.height) }, frame.x + GROUP_PADDING to frame.y + GROUP_PADDING + row)
        group.children.forEachIndexed { i, child -> slots[child.key] = cells[i] }
    }

    /** The cells of a row-major grid of [sizes] starting at [topLeft]; each cell is its item's own size. */
    private fun grid(sizes: List<Size>, topLeft: Pair<Float, Float>): List<Slot> {
        if (sizes.isEmpty()) return emptyList()
        val (x, y) = topLeft
        val columns = columns(sizes.size)
        val columnWidth = sizes.maxOf { it.width }
        val cells = ArrayList<Slot>(sizes.size)
        var top = y
        sizes.chunked(columns).forEach { row ->
            row.forEachIndexed { column, size ->
                cells += Slot(x + column * (columnWidth + GAP), top, size.width, size.height)
            }
            top += row.maxOf { it.height } + GAP
        }
        return cells
    }

    private fun gridSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(0f, 0f)
        val columns = columns(sizes.size)
        val columnWidth = sizes.maxOf { it.width }
        val rows = sizes.chunked(columns)
        val used = minOf(columns, sizes.size)
        return Size(
            width = used * columnWidth + (used - 1) * GAP,
            height = rows.sumOf { row -> row.maxOf { it.height }.toDouble() }.toFloat() + (rows.size - 1) * GAP,
        )
    }

    /** The rectangle around [slots], or null for none. */
    fun union(slots: Collection<Slot>): ComposeBounds? {
        if (slots.isEmpty()) return null
        val left = slots.minOf { it.x }
        val top = slots.minOf { it.y }
        val right = slots.maxOf { it.right }
        val bottom = slots.maxOf { it.bottom }
        return ComposeBounds(left, top, right - left, bottom - top)
    }

    /** [a] and [b] together; either may be null. */
    fun union(a: ComposeBounds?, b: ComposeBounds?): ComposeBounds? = when {
        a == null -> b
        b == null -> a
        else -> union(listOf(a.toSlot(), b.toSlot()))
    }

    private fun ComposeBounds.toSlot() = Slot(x, y, width, height)

    // ---- Content bounds of a scene, read from its JSON -------------------------------------------

    /**
     * The board's content bounds exactly as the renderer's zoom-to-fit computes them
     * (sharedUI `CanvasViewportFit.contentBounds`): the union of every element's DrawBox
     * `bounds()` and every FRAMED document's frame, read from the scene JSON instead of decoded
     * elements. Null when there is nothing. Frameless documents are not in it (they are laid out
     * relative to it, by [placeFrameless]).
     */
    fun contentBounds(sceneJson: String): ComposeBounds? = SceneBounds(sceneJson).content()

    /**
     * What a new artifact must stay clear of: [contentBounds], with each element's box grown to
     * what it can really cover (a rotated element's turned corners, a text element's wrapped lines
     * rather than DrawBox's one-line guess) and the frameless documents where [placeFrameless]
     * puts them. This is the bounds the compiler places against.
     */
    fun occupiedBounds(sceneJson: String): ComposeBounds? = SceneBounds(sceneJson).occupied()

    /** DrawBox `Element.bounds()` of one serialized element, optionally grown as [occupiedBounds] says. */
    internal fun elementBounds(element: JsonObject, conservative: Boolean): Slot = ElementBounds(element, conservative).bounds()
}
