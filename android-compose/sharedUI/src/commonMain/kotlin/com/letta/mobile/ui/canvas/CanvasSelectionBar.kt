package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BringToFront
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PaintBucket
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.SendToBack
import com.composables.icons.lucide.Trash2
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The contextual bar at the top of the board, in the way Concepts and Miro show properties for
 * what is selected: stroke colour, fill colour and outline for the selection (or for the tool
 * about to draw a closed shape), then ordering and delete when something is selected.
 *
 * Colour intents go through [dispatch] so [CanvasControlsBridge] applies them to the selection
 * when there is one and to the current tool otherwise.
 */
@Composable
fun CanvasSelectionBar(
    state: ControlsBarState,
    hasSelection: Boolean,
    dispatch: (ControlsBarIntent) -> Unit,
    onBringToFront: () -> Unit,
    onSendToBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorSwatchPicker(
                current = state.strokeColor,
                palette = StrokePalette,
                label = "Stroke color",
                glyph = Lucide.PenLine,
                onPick = { dispatch(ControlsBarIntent.SetStrokeColor(it)) },
                modifier = Modifier.size(BAR_BUTTON),
            )
            if (state.showFillTarget) {
                ColorSwatchPicker(
                    current = state.fillColor ?: Color.Transparent,
                    palette = StrokePalette,
                    label = "Fill color",
                    glyph = Lucide.PaintBucket,
                    allowNone = true,
                    onPick = { dispatch(ControlsBarIntent.SetFillColor(it)) },
                    modifier = Modifier.size(BAR_BUTTON),
                )
                BarButton(
                    icon = Lucide.PenLine,
                    label = if (state.strokeEnabled) "Outline on" else "Outline off",
                    selected = state.strokeEnabled,
                ) { dispatch(ControlsBarIntent.SetStrokeEnabled(!state.strokeEnabled)) }
            }
            if (hasSelection) {
                Divider()
                BarButton(Lucide.BringToFront, "Bring to front", onClick = onBringToFront)
                BarButton(Lucide.SendToBack, "Send to back", onClick = onSendToBack)
                Divider()
                BarButton(Lucide.Trash2, "Delete selection", onClick = onDelete)
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(BAR_BUTTON).semantics { contentDescription = label },
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

private val BAR_BUTTON = 34.dp
