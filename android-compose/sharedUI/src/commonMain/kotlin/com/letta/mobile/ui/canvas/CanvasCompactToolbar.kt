package com.letta.mobile.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.StickyNote
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The phone board's tools: one bar along the bottom edge instead of the rail down the left.
 *
 * A phone is too narrow for sixteen buttons in a row, so the bar keeps the tools a hand switches
 * between constantly - select, pan, draw, text, eraser - as buttons of their own and gathers the
 * shapes behind a single button that shows whichever shape is current. Undo and redo are not here:
 * [CanvasActionsPill] carries them in the compact layout, where the top of the screen has room.
 *
 * The property control opens its panel above the bar rather than beside it, since beside a
 * bottom bar is off the screen.
 */
@Composable
internal fun CanvasCompactToolbar(
    state: ControlsBarState,
    dispatch: (ControlsBarIntent) -> Unit,
    properties: CanvasProperties,
    dispatchProperty: (CanvasPropertyIntent) -> Unit,
    modifier: Modifier = Modifier,
    onAddNote: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        // A full pill, the shape Craft, Freeform and Obsidian's canvas all float their tools in.
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = LettaDimens.Space.hair,
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactPrimaryModes.forEach { (mode, label) ->
                ModeButton(mode, label, state, dispatch)
                if (mode == Mode.PEN) ShapesButton(state, dispatch)
            }
            if (onAddNote != null) {
                ControlButton(Control(Lucide.StickyNote, "Add note"), size = COMPACT_BUTTON, onClick = onAddNote)
            }
            CanvasPropertyControl(
                state = state,
                properties = properties,
                dispatch = dispatch,
                dispatchProperty = dispatchProperty,
                label = "Stroke color",
                placement = PropertyPopoverPlacement.ABOVE,
                modifier = Modifier.size(COMPACT_BUTTON),
            )
        }
    }
}

@Composable
private fun ModeButton(mode: Mode, label: String, state: ControlsBarState, dispatch: (ControlsBarIntent) -> Unit) {
    ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode), size = COMPACT_BUTTON) {
        dispatch(ControlsBarIntent.SelectMode(mode))
    }
}

/** One button for every shape, showing the current one; the menu picks another. */
@Composable
private fun ShapesButton(state: ControlsBarState, dispatch: (ControlsBarIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = CompactShapeModes.firstOrNull { it.first == state.currentMode }
    Box {
        ControlButton(
            Control(iconFor(current?.first ?: Mode.RECTANGLE), "Shapes", selected = current != null),
            size = COMPACT_BUTTON,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CompactShapeModes.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(iconFor(mode), contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
                    onClick = {
                        open = false
                        dispatch(ControlsBarIntent.SelectMode(mode))
                    },
                )
            }
        }
    }
}

/** The tools that keep a button of their own on the phone bar, in bar order. */
internal val CompactPrimaryModes: List<Pair<Mode, String>> =
    CanvasModes.filter { (mode, _) -> mode in setOf(Mode.SELECT, Mode.PAN, Mode.PEN, Mode.TEXT, Mode.ERASER) }

/** The tools the phone bar gathers behind its shapes button. */
internal val CompactShapeModes: List<Pair<Mode, String>> =
    CanvasModes.filter { (mode, _) -> mode !in CompactPrimaryModes.map { it.first } }

/** Big enough for a fingertip and small enough that the bar fits a 360dp phone. */
private val COMPACT_BUTTON = 40.dp
