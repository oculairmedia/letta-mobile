package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.composables.icons.lucide.StickyNote
import com.composables.icons.lucide.Triangle
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Undo2
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The floating tool bar at the foot of the board, in the shape whiteboard tools share: pointer
 * tools, then the things you can put on the board, then the current colour, then undo and redo,
 * each group separated by a hairline.
 *
 * It is drawn with the icon set this module already ships rather than
 * `io.ak1.drawbox.ui.controls.ControlsBar`: that artifact (`io.ak1:drawbox-ui:0.0.1-alpha01`)
 * publishes an Android AAR with classes but **no** `composeResources`, so its own drawables are
 * absent at runtime and the screen died on open with `MissingResourceException`
 * (letta-mobile-r5f3r). DrawBox's canvas itself is unaffected and still used.
 *
 * State and intents stay DrawBox's, so [CanvasControlsBridge] and its tests are unchanged. The
 * note tool is ours: [onAddNote] places a block document on the board, and the button is only
 * offered when a host wires it.
 */
@Composable
fun CanvasControlsBar(
    state: ControlsBarState,
    dispatch: (ControlsBarIntent) -> Unit,
    modifier: Modifier = Modifier,
    onAddNote: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PointerModes.forEach { (mode, label) ->
                ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode)) {
                    dispatch(ControlsBarIntent.SelectMode(mode))
                }
            }
            BarDivider()
            DrawingModes.forEach { (mode, label) ->
                ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode)) {
                    dispatch(ControlsBarIntent.SelectMode(mode))
                }
            }
            if (onAddNote != null) {
                ControlButton(Control(Lucide.StickyNote, "Add note"), onClick = onAddNote)
            }
            BarDivider()
            StrokeColorControl(current = state.strokeColor) { dispatch(ControlsBarIntent.SetStrokeColor(it)) }
            BarDivider()
            ControlButton(Control(Lucide.Undo2, "Undo", enabled = state.canUndo)) { dispatch(ControlsBarIntent.Undo) }
            ControlButton(Control(Lucide.Redo2, "Redo", enabled = state.canRedo)) { dispatch(ControlsBarIntent.Redo) }
        }
    }
}

/**
 * What one button in the bar looks like. Separating this from what the button does keeps the
 * appearance in one value the bar can build per tool, rather than a widening parameter list that
 * every call site has to read positionally.
 */
private data class Control(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
private fun ControlButton(control: Control, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = control.enabled,
        modifier = Modifier.size(BUTTON_SIZE).semantics { contentDescription = control.label },
        colors = if (control.selected) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(imageVector = control.icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

/** The current stroke colour as a swatch; tapping it opens the palette above the bar. */
@Composable
private fun StrokeColorControl(current: Color, onPick: (Color) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val lift = with(LocalDensity.current) { PALETTE_LIFT.roundToPx() }
    Box(modifier = Modifier.size(BUTTON_SIZE), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(current, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                .semantics { contentDescription = "Stroke color" }
                .clickable { open = !open },
        )
        if (open) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -lift),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StrokePalette.forEach { (color, name) ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(color, CircleShape)
                                    .border(
                                        width = if (color == current) 2.dp else 1.dp,
                                        color = if (color == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .semantics { contentDescription = "Color $name" }
                                    .clickable {
                                        onPick(color)
                                        open = false
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The tools that pick and move, in bar order, with the label a click target reads out. */
internal val PointerModes: List<Pair<Mode, String>> = listOf(
    Mode.SELECT to "Select",
    Mode.PAN to "Pan",
)

/** The tools that put something on the board, in bar order. */
internal val DrawingModes: List<Pair<Mode, String>> = listOf(
    Mode.PEN to "Draw",
    Mode.LINE to "Line",
    Mode.ARROW to "Arrow",
    Mode.RECTANGLE to "Rectangle",
    Mode.CIRCLE to "Circle",
    Mode.TRIANGLE to "Triangle",
    Mode.TEXT to "Text",
    Mode.ERASER to "Eraser",
)

/** Every drawing mode the bar offers, for callers that iterate them regardless of group. */
internal val CanvasModes: List<Pair<Mode, String>> = PointerModes + DrawingModes

/** A fixed marker-style palette; the colours whiteboards reach for, plus black and white. */
internal val StrokePalette: List<Pair<Color, String>> = listOf(
    Color(0xFF1F1F1F) to "black",
    Color(0xFF6B7280) to "gray",
    Color(0xFFE5484D) to "red",
    Color(0xFFF59E0B) to "orange",
    Color(0xFFEAB308) to "yellow",
    Color(0xFF22C55E) to "green",
    Color(0xFF3B82F6) to "blue",
    Color(0xFF8B5CF6) to "violet",
    Color(0xFFFFFFFF) to "white",
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

private val BUTTON_SIZE = 38.dp
private val PALETTE_LIFT = 54.dp
