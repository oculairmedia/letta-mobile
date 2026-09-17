package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

/**
 * A secondary page that opens beside the main content, never over it.
 *
 * One chrome for every side page (agent editor, canvas, background tasks, and the destinations
 * still to migrate): `surfaceContainerLow` ground, a title row with optional per-page [actions]
 * and the close glyph, then the page. The left edge is a resize handle; the width survives
 * recomposition within the process, per call site.
 *
 * Shared so Android's large-screen layouts and desktop draw the same thing.
 */
@Composable
fun LettaSidePane(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialWidth: Dp = 540.dp,
    minWidth: Dp = 320.dp,
    maxWidth: Dp = 1100.dp,
    resizable: Boolean = true,
    /** False when the content draws its own title, as the canvas does with its title pill. */
    showHeader: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    var widthDp by rememberSaveable { mutableFloatStateOf(initialWidth.value) }
    val density = LocalDensity.current
    val dragState = rememberDraggableState { deltaPx ->
        // Dragging the left edge to the left (negative delta) makes the pane wider.
        widthDp = (widthDp - deltaPx / density.density).coerceIn(minWidth.value, maxWidth.value)
    }
    Row(modifier = modifier.fillMaxHeight()) {
        if (resizable) {
            Box(
                modifier = Modifier
                    .width(RESIZE_HANDLE_WIDTH)
                    .fillMaxHeight()
                    .pointerHoverIcon(horizontalResizePointerIcon())
                    .draggable(state = dragState, orientation = Orientation.Horizontal)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            )
        }
        Column(
            modifier = Modifier
                .width(widthDp.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            if (showHeader) LettaSidePaneHeader(title = title, onClose = onClose, actions = actions)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

/** The title row alone, for hosts that lay the page out themselves. */
@Composable
fun LettaSidePaneHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
        Icon(
            imageVector = Lucide.X,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).clickable(onClick = onClose),
        )
    }
}

private val RESIZE_HANDLE_WIDTH = 6.dp
