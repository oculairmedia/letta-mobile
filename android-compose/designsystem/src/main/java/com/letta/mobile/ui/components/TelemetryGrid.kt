package com.letta.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.ui.theme.LettaCodeFont
import java.util.Locale

@Composable
fun TelemetryGrid(
    promptTokens: Int,
    completionTokens: Int,
    durationMs: Long,
    costUsd: Double?,
    modifier: Modifier = Modifier,
) {
    val metrics = remember(promptTokens, completionTokens, durationMs) {
        listOf(
            TelemetryMetric(label = "Prompt", value = formatCompactCount(promptTokens), suffix = "tok"),
            TelemetryMetric(label = "Output", value = formatCompactCount(completionTokens), suffix = "tok"),
            TelemetryMetric(label = "Latency", value = formatDurationValue(durationMs), suffix = formatDurationSuffix(durationMs)),
            TelemetryMetric(label = "Speed", value = formatSpeedValue(completionTokens, durationMs), suffix = "tok/s"),
        )
    }

    Surface(
        modifier = modifier,
        shape = LettaCardDefaults.listShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = TelemetryContainerAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TelemetryBorderAlpha),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Telemetry",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.1.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                )
                costUsd?.let { cost ->
                    TelemetryCostChip(costUsd = cost)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                metrics.forEachIndexed { index, metric ->
                    TelemetryMetricItem(
                        metric = metric,
                        modifier = Modifier.weight(1f),
                    )
                    if (index != metrics.lastIndex) {
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TelemetryDividerAlpha),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    metric: TelemetryMetric,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = metric.label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = metric.value,
                style = telemetryNumberStyle(MaterialTheme.typography.titleLarge),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = metric.suffix,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TelemetryCostChip(costUsd: Double) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = formatCostUsd(costUsd),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = LettaCodeFont,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun telemetryNumberStyle(base: TextStyle): TextStyle = base.copy(
    fontFamily = LettaCodeFont,
    fontWeight = FontWeight.Black,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Immutable
private data class TelemetryMetric(
    val label: String,
    val value: String,
    val suffix: String,
)

internal fun formatCompactCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> String.format(Locale.US, "%,d", value)
}

internal fun formatDurationValue(durationMs: Long): String = when {
    durationMs <= 0L -> "—"
    durationMs < 1_000L -> durationMs.toString()
    durationMs < 60_000L -> String.format(Locale.US, "%.1f", durationMs / 1_000.0)
    else -> String.format(Locale.US, "%.1f", durationMs / 60_000.0)
}

internal fun formatDurationSuffix(durationMs: Long): String = when {
    durationMs <= 0L -> "ms"
    durationMs < 1_000L -> "ms"
    durationMs < 60_000L -> "s"
    else -> "m"
}

internal fun formatSpeedValue(completionTokens: Int, durationMs: Long): String {
    if (completionTokens <= 0 || durationMs <= 0L) return "—"
    val tokensPerSecond = completionTokens / (durationMs / 1_000.0)
    return when {
        tokensPerSecond >= 100 -> String.format(Locale.US, "%.0f", tokensPerSecond)
        tokensPerSecond >= 10 -> String.format(Locale.US, "%.1f", tokensPerSecond)
        else -> String.format(Locale.US, "%.2f", tokensPerSecond)
    }
}

internal fun formatCostUsd(costUsd: Double): String = when {
    costUsd < 0.01 -> String.format(Locale.US, "$%.4f", costUsd)
    costUsd < 1.0 -> String.format(Locale.US, "$%.3f", costUsd)
    else -> String.format(Locale.US, "$%.2f", costUsd)
}

private const val TelemetryContainerAlpha = 0.78f
private const val TelemetryBorderAlpha = 0.36f
private const val TelemetryDividerAlpha = 0.30f
