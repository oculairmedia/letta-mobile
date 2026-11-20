package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownTable
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.max as maxOf

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
    /** Spare width beside the column, both sides together; none where the column fills the lane. */
    var spare: Dp by mutableStateOf(0.dp)
        private set

    /**
     * Called from the list's measure. The comparison reads the old value without observing it: an
     * observed read would subscribe the list's own measure to this state, so every write would
     * invalidate the list that made it and the page would re-measure without ever going idle.
     */
    fun update(newSpare: Dp) {
        if (newSpare != Snapshot.withoutReadObservation { spare }) spare = newSpare
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
 * Spare width beside a column capped at [columnMaxWidth] in a lane [laneWidth] wide, less a
 * [gutter] margin on each side. None while the column fills the lane (a phone, a narrow window).
 */
internal fun breakoutSpare(laneWidth: Dp, columnMaxWidth: Dp, gutter: Dp): Dp {
    val column = min(laneWidth, columnMaxWidth)
    return (laneWidth - column - gutter * 2).coerceAtLeast(0.dp)
}

/**
 * How wide a block of [natural] width draws in a column [parent] wide with [spare] beside it: the
 * column's own width when it fits there or nothing is spare, otherwise everything the spare
 * allows, up to [cap]. A block still wider than that scrolls within it.
 */
internal fun breakoutWidth(parent: Dp, natural: Dp, spare: Dp, cap: Dp): Dp {
    if (natural <= parent || spare <= 0.dp) return parent
    return max(parent, min(parent + spare, cap))
}

/**
 * Records the lane this list's rows sit in: its measured width less [lanePadding] on each side (the
 * list's content padding), against a reading column of [columnMaxWidth].
 *
 * A node element compared by value, not a layout lambda: the list's frame recomposes on every
 * scroll (its edge fades read the list state), and a new lambda each time would re-measure the
 * list, which updates that state again, so the page would never go idle.
 */
internal fun Modifier.markdownBreakoutLane(
    lane: MarkdownBreakoutLane,
    columnMaxWidth: Dp,
    lanePadding: Dp,
): Modifier = this then BreakoutLaneElement(lane, columnMaxWidth, lanePadding)

private data class BreakoutLaneElement(
    val lane: MarkdownBreakoutLane,
    val columnMaxWidth: Dp,
    val lanePadding: Dp,
) : ModifierNodeElement<BreakoutLaneNode>() {
    override fun create() = BreakoutLaneNode(lane, columnMaxWidth, lanePadding)

    override fun update(node: BreakoutLaneNode) {
        node.lane = lane
        node.columnMaxWidth = columnMaxWidth
        node.lanePadding = lanePadding
    }
}

/** Writes the lane from the list's real measure only: intrinsic and lookahead passes pass straight through. */
private class BreakoutLaneNode(
    var lane: MarkdownBreakoutLane,
    var columnMaxWidth: Dp,
    var lanePadding: Dp,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        if (constraints.hasBoundedWidth && !isLookingAhead) {
            val spare = breakoutSpare(
                laneWidth = constraints.maxWidth.toDp() - lanePadding * 2,
                columnMaxWidth = columnMaxWidth,
                gutter = LettaSurfaceSizes.wideContentGutter,
            )
            lane.update(spare)
        }
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.maxIntrinsicHeight(width)
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
            parent = parent.toDp(),
            natural = naturalWidth,
            spare = lane.spare,
            cap = LettaSurfaceSizes.wideContentMaxWidth,
        ).roundToPx().coerceAtLeast(parent)
        // In the column it measures exactly as it always did; broken out, at the width it takes.
        val placeable = measurable.measure(
            if (width == parent) constraints else constraints.copy(minWidth = width, maxWidth = width),
        )
        layout(maxOf(placeable.width.coerceAtMost(parent), constraints.minWidth), placeable.height) {
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
    // Remembered, so a recomposition of the table does not hand it a new layout lambda (and a re-measure).
    val breakout = remember(lane, naturalWidth) { Modifier.markdownBreakout(lane, naturalWidth) }
    Box(breakout.testTag(SHARED_MARKDOWN_TABLE_TAG)) {
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
