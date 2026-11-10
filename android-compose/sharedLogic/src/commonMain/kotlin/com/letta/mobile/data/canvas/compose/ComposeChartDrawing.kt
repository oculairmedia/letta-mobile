package com.letta.mobile.data.canvas.compose

import kotlin.math.round

/*
 * A CHART in a CHART_WIDTH frame ([drawnParts]): its title (18), a row for the values, the plot
 * ([CHART_PLOT_HEIGHT] tall, one equal column per point, a baseline at zero) and the point labels
 * under it (muted, 12, centred, wrapping in their column).
 *
 * BAR draws a bar per point from zero, [CHART_BAR_SHARE] of its column wide; LINE draws a polyline
 * through the column centres with a dot on each point. Each point's value is printed over it when
 * every value fits its column on one line (at 12, else 10); otherwise only the largest is printed,
 * at the right of the value row, so nothing overlaps. The scale always includes zero.
 *
 * A plot is passed as its top-left, `(left, top)`.
 */

internal const val CHART_TITLE_FONT = 18.0
internal const val CHART_LABEL_FONT = 12.0
internal const val CHART_PLOT_HEIGHT = 160f
internal const val CHART_TITLE_GAP = 8f
internal const val CHART_LABEL_GAP = 6f
internal const val CHART_BAR_SHARE = 0.6f

/** The value labels' sizes, largest first; when none fits every column, only the largest value is shown. */
internal val CHART_VALUE_FONTS: List<Double> = listOf(12.0, 10.0)

private val chartInner: Float get() = CanvasComposeContract.CHART_WIDTH - 2 * DRAWING_PADDING

private fun columnWidth(chart: ComposeItem.Chart): Float = chartInner / chart.points.size

/** The title's height and the gap under it; 0 for a chart without a title. */
private fun titleBlock(chart: ComposeItem.Chart): Float =
    chart.title?.takeIf { it.isNotBlank() }?.let { textHeight(it, CHART_TITLE_FONT, chartInner) + CHART_TITLE_GAP } ?: 0f

/** The row the values are printed in, over the plot, clear of the dots. */
private fun valueRow(): Float = textHeight("0", CHART_LABEL_FONT, chartInner) + drawnDotRadius()

private fun labelRow(chart: ComposeItem.Chart): Float = chart.points.maxOf { textHeight(it.label, CHART_LABEL_FONT, columnWidth(chart)) }

/** The size every value fits its column at, or null when they do not all fit at any. */
private fun valueFont(chart: ComposeItem.Chart): Double? = CHART_VALUE_FONTS.firstOrNull { font ->
    chart.points.all { fitsOneLine(formatChartValue(it.value, chart.unit), font, columnWidth(chart)) }
}

/** A CHART's height: title, value row, plot, labels and the padding round them. */
internal fun chartHeight(chart: ComposeItem.Chart): Float =
    DRAWING_PADDING + titleBlock(chart) + valueRow() + CHART_PLOT_HEIGHT + CHART_LABEL_GAP + labelRow(chart) + DRAWING_PADDING

/** A CHART's elements in [slot]: its frame, baseline and marks, then its title, values and labels. */
internal fun chartParts(chart: ComposeItem.Chart, slot: Slot): List<DrawnPart> {
    val left = slot.x + DRAWING_PADDING
    val plot = left to slot.y + DRAWING_PADDING + titleBlock(chart) + valueRow()
    return buildList {
        add(drawnFrame(slot))
        add(drawnAxis("axis", left to yOf(chart, plot, 0.0), left + chartInner to yOf(chart, plot, 0.0)))
        addAll(if (chart.style == ComposeChartStyle.BAR) bars(chart, plot) else line(chart, plot))
        chart.title?.takeIf { it.isNotBlank() }?.let {
            val box = Slot(left, slot.y + DRAWING_PADDING, chartInner, titleBlock(chart) - CHART_TITLE_GAP)
            add(drawnText("title", box, drawnLook(it, CHART_TITLE_FONT, CanvasComposeColors.TEXT)))
        }
        addAll(values(chart, plot))
        addAll(labels(chart, plot))
    }
}

/** [value]'s height on the plot, whole: the scale runs from the smallest value to the largest, zero always in. */
private fun yOf(chart: ComposeItem.Chart, plot: Pair<Float, Float>, value: Double): Float {
    val values = chart.points.map { it.value }
    val low = minOf(0.0, values.min())
    val high = maxOf(0.0, values.max()).let { if (it == low) low + 1.0 else it }
    return plot.second + round((high - value) / (high - low) * CHART_PLOT_HEIGHT).toFloat()
}

/** Point [index]'s column, whole: it ends where the next one starts, so neighbours never overlap. */
private fun column(chart: ComposeItem.Chart, plot: Pair<Float, Float>, index: Int): Slot {
    val left = round(plot.first + columnWidth(chart) * index)
    return Slot(left, plot.second, round(plot.first + columnWidth(chart) * (index + 1)) - left, CHART_PLOT_HEIGHT)
}

private fun centreOf(chart: ComposeItem.Chart, plot: Pair<Float, Float>, index: Int): Float =
    round(plot.first + columnWidth(chart) * index + columnWidth(chart) / 2)

private fun bars(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val barWidth = round(columnWidth(chart) * CHART_BAR_SHARE)
    val zero = yOf(chart, plot, 0.0)
    return chart.points.mapIndexedNotNull { i, point ->
        val top = yOf(chart, plot, point.value)
        val box = Slot(centreOf(chart, plot, i) - round(barWidth / 2), top, barWidth, zero - top)
        drawnBar("bar$i", box, CanvasComposeColors.ACCENT, DRAWING_MARK_Z).takeIf { box.height >= 1f }
    }
}

private fun line(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val points = chart.points.mapIndexed { i, point -> centreOf(chart, plot, i) to yOf(chart, plot, point.value) }
    return listOf(drawnPath("line", points)) + points.mapIndexed { i, centre -> drawnDot("dot$i", centre) }
}

/** Each value over its point, or the largest at the right of the value row when they do not all fit. */
private fun values(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val font = valueFont(chart) ?: return listOf(largest(chart, plot))
    val height = textHeight("0", font, columnWidth(chart))
    return chart.points.mapIndexed { i, point ->
        val top = yOf(chart, plot, point.value) - height - drawnDotRadius()
        val look = drawnLook(formatChartValue(point.value, chart.unit), font, CanvasComposeColors.MUTED)
        drawnText("value$i", column(chart, plot, i).copy(y = top, height = height), look, ALIGN_CENTER)
    }
}

private fun largest(chart: ComposeItem.Chart, plot: Pair<Float, Float>): DrawnPart {
    val look = drawnLook(formatChartValue(chart.points.maxOf { it.value }, chart.unit), CHART_LABEL_FONT, CanvasComposeColors.MUTED)
    return drawnText("range", Slot(plot.first, plot.second - valueRow(), chartInner, valueRow()), look, ALIGN_RIGHT)
}

private fun labels(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val top = plot.second + CHART_PLOT_HEIGHT + CHART_LABEL_GAP
    val height = labelRow(chart)
    return chart.points.mapIndexed { i, point ->
        val look = drawnLook(point.label, CHART_LABEL_FONT, CanvasComposeColors.MUTED)
        drawnText("label$i", column(chart, plot, i).copy(y = top, height = height), look, ALIGN_CENTER)
    }
}
