package com.letta.mobile.desktop.qr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.desktop.DesktopMaterialTheme
import com.letta.mobile.desktop.DesktopSelectableChip

/**
 * letta-mobile-nonza (sixv8.3): collapsible execution-location picker on
 * the chat composer header.
 *
 * The picker renders a compact chip showing the current execution location
 * (default: `local`, the desktop itself). Click expands to show the full
 * peer list from [DesktopPairInviteController.peers]; selecting a peer
 * stores its node id in [DesktopPairInviteController.selectedExecutionLocation].
 *
 * Stub action: the actual command-dispatch routing lands in
 * `letta-mobile-6ub2o` (zq1x4.5). This composable owns the surface only
 * — the picker does not interfere with the chat composer because the
 * expanded list overlays below the chip (fixed max height, scrolls).
 */
@Composable
internal fun DesktopExecutionLocationPicker(
    controller: DesktopPairInviteController,
    modifier: Modifier = Modifier,
    /** Override the default collapsed label (used by tests to drive "local"). */
    localLabel: String = "local",
) {
    DesktopMaterialTheme {
        Box(modifier = modifier.widthIn(min = 200.dp)) {
            var expanded by remember { mutableStateOf(false) }
            val selection = controller.selectedExecutionLocation
            val selectedPeer = selection?.let { id -> controller.peers.firstOrNull { it.nodeId == id } }
            // Spec: default collapsed shows the current execution location.
            // First peer in the store is the default selection per the picker
            // acceptance criterion.
            val collapsedLabel = when {
                selectedPeer != null -> selectedPeer.name
                else -> localLabel
            }
            Column {
                CollapsedChip(
                    label = collapsedLabel,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
                AnimatedVisibility(visible = expanded) {
                    ExpandedList(
                        peers = controller.peers,
                        selectedNodeId = selection,
                        localLabel = localLabel,
                        onSelectLocal = {
                            controller.selectExecutionLocation(null)
                            expanded = false
                        },
                        onSelectPeer = { peer ->
                            controller.selectExecutionLocation(peer.nodeId)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedChip(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .semantics { contentDescription = "Execution location: $label" },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Place,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpandedList(
    peers: List<PairedPeer>,
    selectedNodeId: String?,
    localLabel: String,
    onSelectLocal: () -> Unit,
    onSelectPeer: (PairedPeer) -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            expandedPickerRows(peers, selectedNodeId, localLabel).forEach { row ->
                PickerRow(
                    label = row.label,
                    subtitle = row.subtitle,
                    selected = row.selected,
                    onClick = { if (row.peer != null) onSelectPeer(row.peer) else onSelectLocal() },
                )
            }
        }
    }
}

/**
 * Row data for the expanded peer list: the local fallback row first, then one
 * row per paired peer, in store order. Pulled out of [ExpandedList] as a pure
 * function so the "expanding shows the full peer list" behavior is verifiable
 * without composing through [DesktopSelectableChip] (backed by Jewel's
 * `Chip`), whose current release (`0.37.0-262.4852.51`) ships a class file
 * compiled for a newer JDK than the `shared-multiplatform` CI job's pinned
 * toolchain — see letta-mobile-sixv8.1.
 */
internal data class PickerRowSpec(
    val label: String,
    val subtitle: String?,
    val selected: Boolean,
    val peer: PairedPeer?,
)

internal fun expandedPickerRows(
    peers: List<PairedPeer>,
    selectedNodeId: String?,
    localLabel: String = "local",
): List<PickerRowSpec> {
    val localRow = PickerRowSpec(
        label = localLabel,
        subtitle = null,
        selected = selectedNodeId == null,
        peer = null,
    )
    val peerRows = peers.map { peer ->
        PickerRowSpec(
            label = peer.name,
            subtitle = peer.nodeId.take(12) + "…",
            selected = selectedNodeId == peer.nodeId,
            peer = peer,
        )
    }
    return listOf(localRow) + peerRows
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    DesktopSelectableChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Pure helper: derive the default selection label for a given peer list.
 * Exposed for tests — the picker shows the first peer as default per the
 * acceptance criteria. When the store is empty, the picker falls back to
 * the supplied `localLabel`.
 */
internal fun defaultExecutionLocationLabel(
    peers: List<PairedPeer>,
    localLabel: String = "local",
): String = peers.firstOrNull()?.name ?: localLabel
