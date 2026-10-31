package com.letta.mobile.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphView
import com.letta.mobile.ui.components.LettaSwatch
import com.letta.mobile.ui.components.lettaCount
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The legend, which is also the kind filter: one chip per node kind in the graph, its swatch the
 * nodes' colour and its count how many there are. The last enabled kind cannot be switched off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryKindFilterBar(
    view: MemoryGraphView,
    counts: Map<MemoryGraphNodeKind, Int>,
    onToggle: (MemoryGraphNodeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    // Wraps onto a second line on a phone rather than hiding kinds off the edge.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        view.kindsPresent.forEach { kind ->
            val enabled = view.isKindEnabled(kind)
            FilterChip(
                selected = enabled,
                onClick = { onToggle(kind) },
                label = {
                    val count = counts[kind]?.let { " " + lettaCount(it) }.orEmpty()
                    Text(memoryNodeKindLabel(kind) + count, style = MaterialTheme.typography.labelMedium)
                },
                leadingIcon = {
                    val swatch = palette.kindColor(kind)
                    LettaSwatch(if (enabled) swatch else swatch.copy(alpha = LettaDimens.Alpha.disabled))
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
