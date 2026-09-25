package com.letta.mobile.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Entity-type filter (desktop's "Entity Types" bar): one chip per node kind in
 * the graph. The last enabled kind cannot be switched off.
 */
@Composable
internal fun MemoryKindFilterBar(
    view: MemoryGraphView,
    onToggle: (MemoryGraphNodeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        view.kindsPresent.forEach { kind ->
            val enabled = view.isKindEnabled(kind)
            FilterChip(
                selected = enabled,
                onClick = { onToggle(kind) },
                label = { Text(memoryNodeKindLabel(kind), style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    val swatch = palette.kindColor(kind)
                    Box(
                        Modifier.size(LettaDimens.Space.sm).clip(CircleShape)
                            .background(if (enabled) swatch else swatch.copy(alpha = LettaDimens.Alpha.disabled)),
                    )
                },
                modifier = Modifier.semantics { role = Role.Checkbox },
            )
        }
    }
}

/** Zoom in / out / fit. Buttons keep pan/zoom reachable without a pinch or wheel. */
@Composable
internal fun MemoryGraphZoomControls(
    viewportState: MemoryGraphViewportState,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column {
            IconButton(onClick = { viewportState.zoomBy(ZOOM_STEP) }) {
                Icon(LettaIcons.ZoomIn, contentDescription = "Zoom in")
            }
            IconButton(onClick = { viewportState.zoomBy(1f / ZOOM_STEP) }) {
                Icon(LettaIcons.ZoomOut, contentDescription = "Zoom out")
            }
            IconButton(onClick = onFit) {
                Icon(LettaIcons.FitToView, contentDescription = "Fit graph")
            }
        }
    }
}

private const val ZOOM_STEP = 1.4f
