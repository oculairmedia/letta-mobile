package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.ui.theme.LettaDimens

/** What the badge says for [health]: the words a user reads, and the fuller line a screen reader does. */
internal data class CanvasSyncStatusText(val label: String, val description: String)

internal fun canvasSyncStatusText(health: CanvasSyncHealth): CanvasSyncStatusText = when (health) {
    CanvasSyncHealth.Synced -> CanvasSyncStatusText("Synced", "Synced with every device on this host")
    CanvasSyncHealth.Connecting -> CanvasSyncStatusText("Syncing…", "Syncing: sending edits and catching up")
    is CanvasSyncHealth.OfflineQueued -> {
        val edits = if (health.queued == 1) "1 edit" else "${health.queued} edits"
        CanvasSyncStatusText("Offline — $edits queued", "Offline — $edits queued; they sync when the host is back")
    }
    is CanvasSyncHealth.LocalOnly -> CanvasSyncStatusText("Local only — not syncing", "Local only — not syncing. ${health.reason}")
    is CanvasSyncHealth.Failed -> CanvasSyncStatusText("Sync failed", "Sync failed: ${health.reason}")
}

/**
 * Whether this board is shared right now, always on the board: a quiet dot when it is in sync, and
 * words when it is not - so a canvas that stays on this device never looks like one that syncs.
 * Announced to screen readers when it changes.
 */
@Composable
internal fun CanvasSyncStatusBadge(health: CanvasSyncHealth, modifier: Modifier = Modifier) {
    val text = canvasSyncStatusText(health)
    val (dot, words) = when (health) {
        CanvasSyncHealth.Synced -> Color(0xFF22C55E) to false
        CanvasSyncHealth.Connecting -> MaterialTheme.colorScheme.primary to true
        is CanvasSyncHealth.OfflineQueued -> Color(0xFFF59E0B) to true
        is CanvasSyncHealth.LocalOnly -> MaterialTheme.colorScheme.outline to true
        is CanvasSyncHealth.Failed -> MaterialTheme.colorScheme.error to true
    }
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text.description
            liveRegion = LiveRegionMode.Polite
        },
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            if (words) {
                Text(
                    text = text.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
        }
    }
}
