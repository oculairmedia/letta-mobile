package com.letta.mobile.desktop.home

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Plain-Compose bar spark (no chart dependency), scaled to the series' own peak.
 *
 * Bars only — the smoothed area/line variant was removed deliberately: every
 * series here is a count of discrete events per bucket, and a continuous line
 * between two conversation updates draws a trend that does not exist. An empty
 * series draws a hairline baseline instead of nothing so columns stay aligned.
 */
@Composable
internal fun FleetBarSpark(
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        drawBarSpark(values = values, color = color, baseline = baseline)
    }
}

private fun DrawScope.drawBarSpark(values: List<Int>, color: Color, baseline: Color) {
    val max = values.maxOrNull() ?: 0
    if (values.isEmpty() || max <= 0) {
        drawBaseline(baseline)
        return
    }
    val slot = size.width / values.size.toFloat()
    val barWidth = (slot * 0.62f).coerceAtLeast(1f)
    values.forEachIndexed { index, value ->
        // Empty buckets still get a 1px stub so the cadence of the series is legible.
        val height = ((value.toFloat() / max.toFloat()) * (size.height - 1f)).coerceAtLeast(1f)
        drawRect(
            color = if (value > 0) color.copy(alpha = 0.85f) else baseline,
            topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
            size = androidx.compose.ui.geometry.Size(barWidth, height),
        )
    }
}

private fun DrawScope.drawBaseline(color: Color) {
    drawLine(
        color = color,
        start = Offset(0f, size.height - 1f),
        end = Offset(size.width, size.height - 1f),
        strokeWidth = 1f,
    )
}
