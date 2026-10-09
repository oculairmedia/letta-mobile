package com.letta.mobile.ui.screens.runs

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import ca.oculair.meridian.R
import com.letta.mobile.data.model.Run
import java.time.Instant
import java.util.Locale

@Composable
internal fun runCardContainerColor(status: String?): Color {
    return when (status?.trim()?.lowercase(Locale.ROOT)) {
        "error", "failed", "cancelled", "expired" -> MaterialTheme.colorScheme.errorContainer
        "completed" -> MaterialTheme.colorScheme.secondaryContainer
        "running", "active", "created", "pending", "processing", "working", "busy" ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
}

internal fun isActiveRunStatus(status: String?): Boolean {
    val normalized = status?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized in activeRunStatuses
}

internal val activeRunStatuses = setOf(
    "running", "active", "created", "pending", "processing", "working", "busy",
)

internal fun parseInstantMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

internal fun formatElapsedDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

internal fun truncateRunId(id: String): String =
    if (id.length <= 18) id else id.take(8) + "…" + id.takeLast(8)

internal fun RunMonitorUiState?.isDetailLimited(): Boolean = this?.runDetailLimited == true

internal fun RunMonitorUiState?.canCancel(run: Run): Boolean = !isDetailLimited() && !run.isTerminalStatus()

internal fun RunMonitorUiState?.canDelete(run: Run): Boolean = !isDetailLimited() && run.isTerminalStatus()

internal fun Run.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}

/** Explains why run messages, usage, metrics and cancel/delete are missing under iroh://. */
internal fun LazyListScope.runDetailLimitedNote(show: Boolean) {
    if (!show) return
    item {
        Text(
            stringResource(R.string.screen_runs_detail_unavailable_iroh),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
