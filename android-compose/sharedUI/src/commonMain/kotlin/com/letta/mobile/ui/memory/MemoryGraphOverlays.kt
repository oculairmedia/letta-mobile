package com.letta.mobile.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.letta.mobile.ui.components.lettaHorizontalFadingEdges
import com.letta.mobile.ui.components.lettaCount
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The legend, which is also the kind filter, above the canvas: one chip per node kind in the graph, its swatch the
 * nodes' colour and its count how many there are. The last enabled kind cannot be switched off.
 */
@Composable
internal fun MemoryKindFilterBar(
    view: MemoryGraphView,
    counts: Map<MemoryGraphNodeKind, Int>,
    onToggle: (MemoryGraphNodeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    // One row above the canvas (never over it), scrolling sideways when a phone is too narrow.
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Fade whichever edge has more chips beyond it (the rail's shared fade, sideways).
            .lettaHorizontalFadingEdges(
                startFadeAlpha = if (scroll.canScrollBackward) 1f else 0f,
                endFadeAlpha = if (scroll.canScrollForward) 1f else 0f,
                fadeLength = LettaDimens.Space.xxl,
            )
            .horizontalScroll(scroll)
            .padding(horizontal = LettaDimens.Space.lg),
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

/**
 * Zoom in / out / fit: buttons keep pan/zoom reachable without a pinch or wheel. [fitOnly] (a
 * phone, where pinch zooms) keeps just the fit button.
 */
@Composable
internal fun MemoryGraphZoomControls(
    viewportState: MemoryGraphViewportState,
    onFit: () -> Unit,
    fitOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column {
            if (!fitOnly) {
                IconButton(onClick = { viewportState.zoomBy(ZOOM_STEP) }) {
                    Icon(LettaIcons.ZoomIn, contentDescription = "Zoom in")
                }
                IconButton(onClick = { viewportState.zoomBy(1f / ZOOM_STEP) }) {
                    Icon(LettaIcons.ZoomOut, contentDescription = "Zoom out")
                }
            }
            IconButton(onClick = onFit) {
                Icon(LettaIcons.FitToView, contentDescription = "Fit graph")
            }
        }
    }
}

private const val ZOOM_STEP = 1.4f
