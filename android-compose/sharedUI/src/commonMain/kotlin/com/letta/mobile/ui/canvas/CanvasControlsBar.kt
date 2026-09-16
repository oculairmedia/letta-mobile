package com.letta.mobile.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.MousePointer
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Triangle
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Undo2
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The canvas tool bar, drawn with the icon set this module already ships.
 *
 * It replaces `io.ak1.drawbox.ui.controls.ControlsBar`: that artifact
 * (`io.ak1:drawbox-ui:0.0.1-alpha01`) publishes an Android AAR with classes but **no**
 * `composeResources`, so its own drawables (`undo.xml`, …) are absent at runtime and the screen
 * died on open with `MissingResourceException` (letta-mobile-r5f3r). DrawBox's canvas itself is
 * unaffected and still used.
 *
 * State and intents stay DrawBox's, so [CanvasControlsBridge] and its tests are unchanged.
 */
@Composable
fun CanvasControlsBar(
    state: ControlsBarState,
    dispatch: (ControlsBarIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CanvasModes.forEach { (mode, label) ->
                ControlButton(
                    icon = iconFor(mode),
                    label = label,
                    selected = state.currentMode == mode,
                    onClick = { dispatch(ControlsBarIntent.SelectMode(mode)) },
                )
            }
            ControlButton(
                icon = Lucide.Undo2,
                label = "Undo",
                enabled = state.canUndo,
                onClick = { dispatch(ControlsBarIntent.Undo) },
            )
            ControlButton(
                icon = Lucide.Redo2,
                label = "Redo",
                enabled = state.canRedo,
                onClick = { dispatch(ControlsBarIntent.Redo) },
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).semantics { contentDescription = label },
        colors = if (selected) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** The drawing modes the bar offers, in bar order, with the label a click target reads out. */
internal val CanvasModes: List<Pair<Mode, String>> = listOf(
    Mode.SELECT to "Select",
    Mode.PAN to "Pan",
    Mode.PEN to "Draw",
    Mode.LINE to "Line",
    Mode.ARROW to "Arrow",
    Mode.RECTANGLE to "Rectangle",
    Mode.CIRCLE to "Circle",
    Mode.TRIANGLE to "Triangle",
    Mode.TEXT to "Text",
    Mode.ERASER to "Eraser",
)

private fun iconFor(mode: Mode): ImageVector = when (mode) {
    Mode.SELECT -> Lucide.MousePointer
    Mode.PAN -> Lucide.Hand
    Mode.PEN -> Lucide.Pencil
    Mode.LINE -> Lucide.Minus
    Mode.ARROW -> Lucide.ArrowRight
    Mode.RECTANGLE -> Lucide.Square
    Mode.CIRCLE -> Lucide.Circle
    Mode.TRIANGLE -> Lucide.Triangle
    Mode.TEXT -> Lucide.Type
    Mode.ERASER -> Lucide.Eraser
    else -> Lucide.Pencil
}
