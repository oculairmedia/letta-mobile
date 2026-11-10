package com.letta.mobile.data.canvas.compose

import kotlin.math.abs
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

private const val HUNDREDTHS = 100L
private const val TENS = 10L
private const val DIGIT_GROUP = 3

private val chartInner: Float get() = CanvasComposeContract.CHART_WIDTH - 2 * DRAWING_PADDING

private fun columnWidth(chart: ComposeItem.Chart): Float = chartInner / chart.points.size

private fun muted(text: String, font: Double): TextLook = TextLook(text, font, CanvasComposeColors.MUTED, DRAWING_TEXT_Z)

private fun chartTitle(chart: ComposeItem.Chart): TextLook? = chart.title?.takeIf { it.isNotBlank() }?.let {
    TextLook(it, CHART_TITLE_FONT, CanvasComposeColors.TEXT, DRAWING_TEXT_Z)
}

/** The title's height and the gap under it; 0 for a chart without a title. */
private fun titleBlock(chart: ComposeItem.Chart): Float = chartTitle(chart)?.let { textHeight(it, chartInner) + CHART_TITLE_GAP } ?: 0f

/** The row the values are printed in, over the plot, clear of the dots. */
private fun valueRow(): Float = textHeight(muted("0", CHART_LABEL_FONT), chartInner) + drawnDotRadius()

private fun labelRow(chart: ComposeItem.Chart): Float =
    chart.points.maxOf { textHeight(muted(it.label, CHART_LABEL_FONT), columnWidth(chart)) }

/** The size every value fits its column at, or null when they do not all fit at any. */
private fun valueFont(chart: ComposeItem.Chart): Double? = CHART_VALUE_FONTS.firstOrNull { font ->
    chart.points.all { fitsOneLine(muted(formatChartValue(it.value, chart.unit), font), columnWidth(chart)) }
}

/** A CHART's height: title, value row, plot, labels and the padding round them. */
internal fun chartHeight(chart: ComposeItem.Chart): Float =
    DRAWING_PADDING + titleBlock(chart) + valueRow() + CHART_PLOT_HEIGHT + CHART_LABEL_GAP + labelRow(chart) + DRAWING_PADDING

/** A CHART's elements in [slot]: its frame, baseline and marks, then its title, values and labels. */
internal fun chartParts(chart: ComposeItem.Chart, slot: Slot): List<DrawnPart> {
    val left = slot.x + DRAWING_PADDING
    val plot = left to slot.y + DRAWING_PADDING + titleBlock(chart) + valueRow()
    val zero = plotY(chart, plot, 0.0)
    return buildList {
        add(drawnFrame(slot))
        add(drawnAxis(left to zero, left + chartInner to zero))
        addAll(if (chart.style == ComposeChartStyle.BAR) bars(chart, plot) else line(chart, plot))
        chartTitle(chart)?.let {
            add(drawnText("title" to Slot(left, slot.y + DRAWING_PADDING, chartInner, titleBlock(chart) - CHART_TITLE_GAP), it))
        }
        addAll(values(chart, plot))
        addAll(labels(chart, plot))
    }
}

/** [value]'s height on the plot, whole: the scale runs from the smallest value to the largest, zero always in. */
private fun plotY(chart: ComposeItem.Chart, plot: Pair<Float, Float>, value: Double): Float {
    val values = chart.points.map { it.value }
    val low = minOf(0.0, values.min())
    val high = maxOf(0.0, values.max()).let { if (it == low) low + 1.0 else it }
    return plot.second + round((high - value) / (high - low) * CHART_PLOT_HEIGHT).toFloat()
}

/** [point]'s height on the plot. */
private fun pointY(chart: ComposeItem.Chart, plot: Pair<Float, Float>, point: ComposeChartPoint): Float = plotY(chart, plot, point.value)

/** Each point's column, whole: one ends where the next starts, so neighbours never overlap. */
private fun columns(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<Slot> {
    val width = columnWidth(chart)
    return chart.points.indices.map { index ->
        val left = round(plot.first + width * index)
        Slot(left, plot.second, round(plot.first + width * (index + 1)) - left, CHART_PLOT_HEIGHT)
    }
}

/** Each point's column centre, whole. */
private fun centres(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<Float> {
    val width = columnWidth(chart)
    return chart.points.indices.map { index -> round(plot.first + width * index + width / 2) }
}

private fun bars(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val barWidth = round(columnWidth(chart) * CHART_BAR_SHARE)
    val zero = plotY(chart, plot, 0.0)
    val centres = centres(chart, plot)
    return chart.points.mapIndexedNotNull { i, point ->
        val top = pointY(chart, plot, point)
        val box = Slot(centres[i] - round(barWidth / 2), top, barWidth, zero - top)
        drawnBar("bar$i" to box, CanvasComposeColors.ACCENT to DRAWING_MARK_Z).takeIf { box.height >= 1f }
    }
}

private fun line(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val centres = centres(chart, plot)
    val points = chart.points.mapIndexed { i, point -> centres[i] to pointY(chart, plot, point) }
    return listOf(drawnPath(points)) + drawnDots(points)
}

/** Each value over its point, or the largest at the right of the value row when they do not all fit. */
private fun values(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val font = valueFont(chart) ?: return listOf(largest(chart, plot))
    val height = textHeight(muted("0", font), columnWidth(chart))
    val columns = columns(chart, plot)
    return chart.points.mapIndexed { i, point ->
        val top = pointY(chart, plot, point) - height - drawnDotRadius()
        val look = muted(formatChartValue(point.value, chart.unit), font)
        drawnText("value$i" to columns[i].copy(y = top, height = height), look, ALIGN_CENTER)
    }
}

private fun largest(chart: ComposeItem.Chart, plot: Pair<Float, Float>): DrawnPart {
    val look = muted(formatChartValue(chart.points.maxOf { it.value }, chart.unit), CHART_LABEL_FONT)
    return drawnText("range" to Slot(plot.first, plot.second - valueRow(), chartInner, valueRow()), look, ALIGN_RIGHT)
}

private fun labels(chart: ComposeItem.Chart, plot: Pair<Float, Float>): List<DrawnPart> {
    val top = plot.second + CHART_PLOT_HEIGHT + CHART_LABEL_GAP
    val height = labelRow(chart)
    val columns = columns(chart, plot)
    return chart.points.mapIndexed { i, point ->
        drawnText("label$i" to columns[i].copy(y = top, height = height), muted(point.label, CHART_LABEL_FONT), ALIGN_CENTER)
    }
}

/**
 * A number as a chart prints it, the same on every target (Kotlin/JS would print `3` where the
 * JVM prints `3.0`): at most two decimals, trailing zeros dropped, thousands grouped with commas,
 * then [unit].
 */
internal fun formatChartValue(value: Double, unit: String? = null): String {
    val scaled = round(value * HUNDREDTHS).toLong()
    val magnitude = abs(scaled)
    val whole = (magnitude / HUNDREDTHS).toString().reversed().chunked(DIGIT_GROUP).joinToString(",").reversed()
    val fraction = magnitude % HUNDREDTHS
    val decimals = when {
        fraction == 0L -> ""
        fraction % TENS == 0L -> "." + (fraction / TENS)
        else -> "." + fraction.toString().padStart(2, '0')
    }
    val sign = if (scaled < 0) "-" else ""
    return sign + whole + decimals + unit.orEmpty()
}
