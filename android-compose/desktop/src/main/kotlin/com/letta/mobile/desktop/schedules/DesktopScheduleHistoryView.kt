package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleReliability
import com.letta.mobile.ui.theme.customColors

@Composable
internal fun HistoryView(summary: HistorySummary) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { StatsCard(summary) }
        item {
            Text(
                "RELIABILITY · LAST 12 RUNS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.customColors.onSurfaceMutedColor,
            )
        }
        items(items = summary.schedules, key = { it.scheduleId }) { rel ->
            ReliabilityRow(rel)
        }
        if (summary.totalRuns == 0) {
            item {
                Text(
                    "No run history recorded yet — schedules will populate this as they fire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                )
            }
        }
    }
}

@Composable
private fun ReliabilityRow(rel: ScheduleReliability) {
    val ok = rel.failed == 0
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rel.scheduleName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${rel.succeeded}/${rel.total}",
                style = MaterialTheme.typography.labelMedium,
                color = if (ok) MaterialTheme.customColors.successColor else MaterialTheme.customColors.runningColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        ReliabilityStrip(rel.squares)
    }
}

@Composable
internal fun StatsCard(summary: HistorySummary) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 18.dp),
    ) {
        StatCell("${summary.totalRuns}", "runs · 30d", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        StatCell(
            summary.overallSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—",
            "success",
            MaterialTheme.customColors.successColor,
            Modifier.weight(1f),
        )
        StatCell("—", "avg run", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
internal fun StatCell(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
internal fun ReliabilityStrip(squares: List<Boolean?>, count: Int = 12) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val padded = (squares.takeLast(count) + List(count) { null }).take(count)
        padded.forEach { ok ->
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(reliabilitySquareColor(ok)))
        }
    }
}

@Composable
private fun reliabilitySquareColor(ok: Boolean?): Color = when (ok) {
    true -> MaterialTheme.customColors.successColor
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.surfaceContainerHighest
}
