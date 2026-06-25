package com.letta.mobile.desktop.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelDisplayStatus
import com.letta.mobile.desktop.components.DesktopCatalogCard
import com.letta.mobile.desktop.components.DesktopRefreshAction
import com.letta.mobile.desktop.components.DesktopCatalogGridPadding
import com.letta.mobile.desktop.components.DesktopCatalogHeader
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.components.DesktopInfoBox
import com.letta.mobile.desktop.components.DesktopPill
import com.letta.mobile.desktop.components.desktopCardGrid
import com.letta.mobile.ui.theme.customColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
fun DesktopChannelLibrarySurface(
    state: DesktopChannelLibraryState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<ChannelDisplayStatus?>(null) }

    val statusesPresent = ChannelDisplayStatus.entries.filter { s -> state.channels.any { it.status == s } }
    val filtered = state.channels.filter { channel ->
        (statusFilter == null || channel.status == statusFilter) &&
            (
                query.isBlank() ||
                    channel.title.contains(query, true) ||
                    channel.subtitle.contains(query, true) ||
                    channel.detailText.contains(query, true)
                )
    }
    // Group by connection status — each status is a labelled section in the grid.
    val sections = statusesPresent.mapNotNull { status ->
        filtered.filter { it.status == status }.takeIf { it.isNotEmpty() }?.let { status.label to it }
    }

    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        DesktopCatalogHeader(
            title = "Channels",
            query = query,
            onQuery = { query = it },
            searchPlaceholder = "Search channels",
            actions = { DesktopRefreshAction(onRefresh) },
            chips = {
                DesktopChipTab("All channels", statusFilter == null) { statusFilter = null }
                statusesPresent.forEach { status ->
                    DesktopChipTab(status.label, statusFilter == status) { statusFilter = status }
                }
            },
        )
        when {
            state.channels.isEmpty() -> DesktopInfoBox("No live channels are available for the active backend.")
            filtered.isEmpty() -> DesktopInfoBox("No channels match your filter.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = DesktopCatalogGridPadding,
            ) {
                desktopCardGrid(sections, keyOf = { it.id }) { channel, cardModifier ->
                    ChannelCard(channel, cardModifier)
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: ChannelDisplayItem, modifier: Modifier) {
    val accent = channel.status.accent()
    // Keep it terse: the status pill already says the status, so don't repeat it
    // in the description — show the detail text only when it adds information.
    val description = channel.detailText
        .takeIf { it.isNotBlank() && !it.equals(channel.status.label, ignoreCase = true) }
        ?: channel.subtitle.takeIf { it.isNotBlank() && !it.equals(channel.status.label, ignoreCase = true) }
    DesktopCatalogCard(
        title = channel.title,
        description = description,
        accent = accent,
        onClick = {},
        modifier = modifier,
    ) {
        // Status pill plus any diagnostic metadata (Code 4401, Auth, catalog,
        // device ids, …) the mapper attached — these carry the failure /
        // transport detail and were dropped when only the pill was shown
        // (Codex review).
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopPill(channel.status.label, accent)
            channel.metadataLabels
                .filterNot { it.equals(channel.status.label, ignoreCase = true) }
                .forEach { label -> DesktopPill(label, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChannelDisplayStatus.accent(): Color = when (this) {
    ChannelDisplayStatus.Connected -> MaterialTheme.customColors.successColor
    ChannelDisplayStatus.Connecting -> MaterialTheme.customColors.runningColor
    ChannelDisplayStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    ChannelDisplayStatus.Disconnected -> MaterialTheme.colorScheme.error
}
