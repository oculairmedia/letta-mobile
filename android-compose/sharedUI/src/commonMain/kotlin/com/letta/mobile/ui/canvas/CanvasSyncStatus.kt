package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.ui.components.foundation.LettaStatusDot
import com.letta.mobile.ui.components.foundation.lettaPanelBorder
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaStatusTone

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

/** The status dot's tone: success in sync, running while syncing, warning offline, idle local only, error failed. */
internal fun canvasSyncTone(health: CanvasSyncHealth): LettaStatusTone = when (health) {
    CanvasSyncHealth.Synced -> LettaStatusTone.Success
    CanvasSyncHealth.Connecting -> LettaStatusTone.Running
    is CanvasSyncHealth.OfflineQueued -> LettaStatusTone.Warning
    is CanvasSyncHealth.LocalOnly -> LettaStatusTone.Idle
    is CanvasSyncHealth.Failed -> LettaStatusTone.Error
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
        LettaStatusDot(canvasSyncTone(health))
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
    val tone = canvasSyncTone(health)
    val words = health != CanvasSyncHealth.Synced
    // In the header bar it is part of the bar; on its own it is a pill of its own.
    val inBar = LocalInHeaderBar.current
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text.description
            liveRegion = LiveRegionMode.Polite
        },
        shape = MaterialTheme.shapes.medium,
        color = if (inBar) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = if (inBar) null else lettaPanelBorder(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            LettaStatusDot(tone)
            if (words) {
                Text(
                    text = text.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SYNC_BADGE_MAX_WIDTH),
                )
            }
        }
    }
}
