package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownTable
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.max
import kotlin.math.min

/**
 * The spare width beside a centred reading column that a wide block (a table) may break out into.
 *
 * The list that owns the column writes it while it measures ([markdownBreakoutLane]), before it
 * measures its rows, so a row reads the current value in the same layout pass: a table lands at its
 * broken-out width on its first frame. It is read only in the layout phase, so a window resize
 * re-measures the tables without recomposing a row.
 */
@Stable
internal class MarkdownBreakoutLane {
    /** Spare width beside the column, both sides together, in px; 0 where the column fills the lane. */
    var sparePx: Int by mutableIntStateOf(0)
        private set

    fun update(laneWidthPx: Int, columnMaxWidthPx: Int, gutterPx: Int) {
        val spare = breakoutSpare(laneWidthPx, columnMaxWidthPx, gutterPx)
        if (spare != sparePx) sparePx = spare
    }
}

/**
 * The lane a list offers its rows. Only rows that opt in pass it on to their markdown as
 * [LocalMarkdownBreakoutLane] (an agent's prose reply): a table inside a card stays in its card.
 */
internal val LocalTimelineBreakoutLane = staticCompositionLocalOf<MarkdownBreakoutLane?> { null }

/** The lane this markdown's tables may break out into; null keeps every table inside its column. */
internal val LocalMarkdownBreakoutLane = staticCompositionLocalOf<MarkdownBreakoutLane?> { null }

/**
 * Spare width beside a column capped at [columnMaxWidthPx] in a lane [laneWidthPx] wide, less a
 * [gutterPx] margin on each side. None while the column fills the lane (a phone, a narrow window).
 */
internal fun breakoutSpare(laneWidthPx: Int, columnMaxWidthPx: Int, gutterPx: Int): Int {
    val column = min(laneWidthPx, columnMaxWidthPx)
    return (laneWidthPx - column - 2 * gutterPx).coerceAtLeast(0)
}

/**
 * How wide a block of [naturalPx] draws in a column [parentPx] wide with [sparePx] beside it: the
 * column's own width when it fits there or nothing is spare, otherwise everything the spare
 * allows, up to [capPx]. A block still wider than that scrolls within it.
 */
internal fun breakoutWidth(parentPx: Int, naturalPx: Int, sparePx: Int, capPx: Int): Int {
    if (naturalPx <= parentPx || sparePx <= 0) return parentPx
    return max(parentPx, min(parentPx + sparePx, capPx))
}

/**
 * Records the lane this list's rows sit in: its measured width less [lanePadding] on each side (the
 * list's content padding), against a reading column of [columnMaxWidth].
 */
internal fun Modifier.markdownBreakoutLane(
    lane: MarkdownBreakoutLane,
    columnMaxWidth: Dp,
    lanePadding: Dp,
): Modifier = layout { measurable, constraints ->
    if (constraints.hasBoundedWidth) {
        lane.update(
            laneWidthPx = constraints.maxWidth - 2 * lanePadding.roundToPx(),
            columnMaxWidthPx = columnMaxWidth.roundToPx(),
            gutterPx = LettaSurfaceSizes.wideContentGutter.roundToPx(),
        )
    }
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Lets a block of [naturalWidth] break out of its column into [lane]'s spare width, centred on the
 * column. It still reports the column's width to its parent, so the text around it keeps its
 * layout; only the block draws (and takes input) past the column's edges.
 */
internal fun Modifier.markdownBreakout(lane: MarkdownBreakoutLane?, naturalWidth: Dp): Modifier {
    if (lane == null) return this
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val parent = constraints.maxWidth
        val width = breakoutWidth(
            parentPx = parent,
            naturalPx = naturalWidth.roundToPx(),
            sparePx = lane.sparePx,
            capPx = LettaSurfaceSizes.wideContentMaxWidth.roundToPx(),
        )
        // In the column it measures exactly as it always did; broken out, at the width it takes.
        val placeable = measurable.measure(
            if (width == parent) constraints else constraints.copy(minWidth = width, maxWidth = width),
        )
        layout(max(placeable.width.coerceAtMost(parent), constraints.minWidth), placeable.height) {
            placeable.place((parent - width) / 2, 0)
        }
    }
}

/**
 * The renderer's own table, free to break out of the column. Its natural width is what the
 * renderer itself asks for (a cell width per column), and its column count comes from the header
 * row, which is whole before any body row streams in: rows landing never change the width.
 */
@Composable
internal fun SharedMarkdownTable(model: MarkdownComponentModel) {
    val lane = LocalMarkdownBreakoutLane.current
    val columns = remember(model.node) { model.node.tableColumnCount() }
    val naturalWidth = LocalMarkdownDimens.current.tableCellWidth * columns
    Box(Modifier.markdownBreakout(lane, naturalWidth).testTag(SHARED_MARKDOWN_TABLE_TAG)) {
        MarkdownTable(content = model.content, node = model.node, style = model.typography.table)
    }
}

/** A shared markdown table, at the width it draws (broken out or not). */
internal const val SHARED_MARKDOWN_TABLE_TAG = "shared-markdown-table"

private fun ASTNode.tableColumnCount(): Int =
    children.firstOrNull { it.type == GFMElementTypes.HEADER }
        ?.children
        ?.count { it.type == GFMTokenTypes.CELL }
        ?: 0
