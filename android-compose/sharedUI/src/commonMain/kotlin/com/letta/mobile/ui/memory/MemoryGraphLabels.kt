package com.letta.mobile.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.graph.GraphDimens
import kotlin.math.abs
import kotlin.math.max

/**
 * A node's label, measured once per graph/theme: the short form, and the full title the focused
 * node shows (hovered with a pointer, or tapped/selected on touch, so nothing is hover-only).
 */
@Immutable
internal class MemoryNodeLabel(val short: TextLayoutResult, val full: TextLayoutResult)

@Composable
internal fun rememberMemoryNodeLabels(view: MemoryGraphView, palette: MemoryGraphPalette, compact: Boolean): Map<String, MemoryNodeLabel> {
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val resting = if (compact) LettaDimens.Pane.nodeLabelMaxWidth else GraphDimens.labelWidth
    val shortWidth = with(density) { resting.roundToPx() }
    val fullWidth = with(density) { (if (compact) resting * 2 else GraphDimens.labelFocusedWidth).roundToPx() }
    val maxChars = if (compact) COMPACT_CHARS else MemoryLabelText.MAX_CHARS
    return remember(view, palette, typography, density, shortWidth) {
        view.nodes.associate { node ->
            val root = node.kind == MemoryGraphNodeKind.Agent || node.kind == MemoryGraphNodeKind.Backend
            val style = if (root) {
                typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = palette.label)
            } else {
                typography.labelMedium.copy(color = palette.label)
            }
            node.id to MemoryNodeLabel(
                short = measurer.label(MemoryLabelText.shorten(node.title, maxChars), style, LabelBox(shortWidth, 1)),
                full = measurer.label(node.title, style, LabelBox(fullWidth, FULL_LABEL_LINES)),
            )
        }
    }
}

/** A label's box: its width cap in px and how many lines it may wrap to. */
private class LabelBox(val maxWidthPx: Int, val maxLines: Int)

private fun TextMeasurer.label(text: String, style: TextStyle, box: LabelBox): TextLayoutResult =
    measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = box.maxLines,
        constraints = Constraints(maxWidth = box.maxWidthPx),
    )

/** Truncation that keeps the part of a title that tells nodes apart. */
internal object MemoryLabelText {
    const val MAX_CHARS = 24
    private const val ELLIPSIS = "…"
    private const val ID_TAIL = 6

    /**
     * Prose cuts at a word boundary ("Emmanuel authorized…"); a single long token (an address,
     * an id, a URI) keeps its head and tail ("iroh://3304…5c111a"), where ids differ.
     */
    fun shorten(text: String, maxChars: Int = MAX_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return if (' ' in trimmed) cutAtWord(trimmed, maxChars) else keepEnds(trimmed, maxChars)
    }

    private fun cutAtWord(text: String, maxChars: Int): String {
        val cut = text.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val head = if (lastSpace >= maxChars / 2) cut.substring(0, lastSpace) else cut
        return head.trimEnd(' ', ',', ';', ':', '.', '-') + ELLIPSIS
    }

    private fun keepEnds(text: String, maxChars: Int): String {
        val tail = ID_TAIL.coerceAtMost(maxChars / 3)
        val head = maxChars - tail - ELLIPSIS.length
        return text.take(head) + ELLIPSIS + text.takeLast(tail)
    }
}

/**
 * Where a label goes: just outside its node, on the side the node's edges leave emptiest (the
 * bisector of the widest gap between them), so no edge runs through the text. A leaf's label
 * points away from its hub; a node without edges is labelled below.
 */
internal object MemoryLabelPlacement {
    /** Unit direction from the node centre toward its label, from the screen offsets of its neighbours. */
    fun direction(centre: Offset, neighbours: List<Offset>): Offset {
        if (neighbours.isEmpty()) return Offset(0f, 1f)
        val angles = neighbours.map { kotlin.math.atan2(it.y - centre.y, it.x - centre.x) }.sorted()
        var bestGap = -1f
        var bestMid = 0f
        angles.forEachIndexed { index, angle ->
            val next = if (index == angles.lastIndex) angles.first() + TWO_PI else angles[index + 1]
            val gap = next - angle
            if (gap > bestGap) {
                bestGap = gap
                bestMid = angle + gap / 2f
            }
        }
        return Offset(kotlin.math.cos(bestMid), kotlin.math.sin(bestMid))
    }

    /**
     * Top-left of a [chip]-sized label hung [reach] from [centre] toward [direction]: the box's
     * near corner (or edge midpoint) touches that anchor point, so the box never covers the node.
     */
    fun topLeft(centre: Offset, reach: Float, direction: Offset, chip: Size): Offset {
        val anchor = Offset(centre.x + direction.x * reach, centre.y + direction.y * reach)
        val square = max(abs(direction.x), abs(direction.y)).coerceAtLeast(MIN_COMPONENT)
        val sx = direction.x / square
        val sy = direction.y / square
        return Offset(anchor.x - chip.width / 2f + sx * chip.width / 2f, anchor.y - chip.height / 2f + sy * chip.height / 2f)
    }

    private const val TWO_PI = (2 * kotlin.math.PI).toFloat()
    private const val MIN_COMPONENT = 0.001f
}

private const val FULL_LABEL_LINES = 3
private const val COMPACT_CHARS = 16
