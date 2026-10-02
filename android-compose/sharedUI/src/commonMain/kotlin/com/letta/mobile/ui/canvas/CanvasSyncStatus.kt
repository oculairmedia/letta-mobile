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

/** The status dot's colour: green in sync, amber offline, the scheme's own for the rest. */
@Composable
internal fun canvasSyncDotColor(health: CanvasSyncHealth): Color = when (health) {
    CanvasSyncHealth.Synced -> Color(0xFF22C55E)
    CanvasSyncHealth.Connecting -> MaterialTheme.colorScheme.primary
    is CanvasSyncHealth.OfflineQueued -> Color(0xFFF59E0B)
    is CanvasSyncHealth.LocalOnly -> MaterialTheme.colorScheme.outline
    is CanvasSyncHealth.Failed -> MaterialTheme.colorScheme.error
}

/**
 * The sync status as the first line of the board's overflow menu, where the phone's chat page
 * keeps it once the top of the board is clear: the dot and the words, read out in full.
 */
@Composable
internal fun CanvasSyncStatusLine(health: CanvasSyncHealth, modifier: Modifier = Modifier) {
    val text = canvasSyncStatusText(health)
    Row(
        modifier = modifier
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .clearAndSetSemantics { contentDescription = text.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Box(Modifier.size(SYNC_DOT).background(canvasSyncDotColor(health), CircleShape))
        Text(
            text = text.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Whether this board is shared right now, always on the board: a quiet dot when it is in sync, and
 * words when it is not - so a canvas that stays on this device never looks like one that syncs.
 * Announced to screen readers when it changes.
 */
@Composable
internal fun CanvasSyncStatusBadge(health: CanvasSyncHealth, modifier: Modifier = Modifier) {
    val text = canvasSyncStatusText(health)
    val dot = canvasSyncDotColor(health)
    val words = health != CanvasSyncHealth.Synced
    // In the header bar it is part of the bar; on its own it is a pill of its own.
    val inBar = LocalInHeaderBar.current
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text.description
            liveRegion = LiveRegionMode.Polite
        },
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = if (inBar) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = if (inBar) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            Box(Modifier.size(SYNC_DOT).background(dot, CircleShape))
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

/** The status dot, on the badge, in the menu and on the phone bar's more button. */
internal val SYNC_DOT = 8.dp
