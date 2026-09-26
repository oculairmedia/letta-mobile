package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.composables.icons.lucide.StickyNote
import com.composables.icons.lucide.Triangle
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Undo2
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The tool rail down the left of the board, the way Concepts and Miro keep their tools: pointer
 * tools, then the things you can put on the board, then the current stroke colour, then undo and
 * redo, each group separated by a hairline. A rail leaves the foot of the board free, so the
 * status line and zoom pill never collide with it however narrow the host pane is.
 *
 * It is drawn with the icon set this module already ships rather than
 * `io.ak1.drawbox.ui.controls.ControlsBar`: that artifact (`io.ak1:drawbox-ui:0.0.1-alpha01`)
 * publishes an Android AAR with classes but **no** `composeResources`, so its own drawables are
 * absent at runtime and the screen died on open with `MissingResourceException`
 * (letta-mobile-r5f3r). DrawBox's canvas itself is unaffected and still used.
 *
 * State and intents stay DrawBox's, so [CanvasControlsBridge] and its tests are unchanged. The
 * text and note tools are ours: [onAddText] places a plain block-editor text element and
 * [onAddNote] a sticky note, both block documents; each button is only offered when a host wires
 * it. DrawBox's own TEXT mode is not in the rail: text on this board is the block editor.
 */
@Composable
fun CanvasControlsBar(
    state: ControlsBarState,
    dispatch: (ControlsBarIntent) -> Unit,
    modifier: Modifier = Modifier,
    properties: CanvasProperties? = null,
    dispatchProperty: (CanvasPropertyIntent) -> Unit = {},
    onAddNote: (() -> Unit)? = null,
    onAddText: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = LettaDimens.Space.hair,
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PointerModes.forEach { (mode, label) ->
                ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode)) {
                    dispatch(ControlsBarIntent.SelectMode(mode))
                }
            }
            RailDivider()
            DrawingModes.forEach { (mode, label) ->
                ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode)) {
                    dispatch(ControlsBarIntent.SelectMode(mode))
                }
            }
            if (onAddNote != null) {
                ControlButton(Control(Lucide.StickyNote, "Add note"), onClick = onAddNote)
            }
            RailDivider()
            // The rail's swatch is the same master control the selection bar opens: one place
            // for every colour and property, targeting the selection or else the current tool.
            CanvasPropertyControl(
                state = state,
                properties = properties ?: CanvasProperties(
                    selectionCount = 0,
                    strokeWidth = 4f,
                    opacity = 1f,
                    strokeStyle = io.ak1.drawbox.domain.model.StrokeStyle.SOLID,
                    cornerRadius = 0f,
                    showCornerRadius = false,
                    fontSize = DEFAULT_FONT_SIZE,
                    fontFamily = io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys.SANS,
                    textAlignment = io.ak1.drawbox.domain.model.TextAlignment.LEFT,
                    showFontSize = false,
                ),
                dispatch = dispatch,
                dispatchProperty = dispatchProperty,
                label = "Stroke color",
                placement = PropertyPopoverPlacement.BESIDE,
                modifier = Modifier.size(BUTTON_SIZE),
            )
            RailDivider()
            ControlButton(Control(Lucide.Undo2, "Undo", enabled = state.canUndo)) { dispatch(ControlsBarIntent.Undo) }
            ControlButton(Control(Lucide.Redo2, "Redo", enabled = state.canRedo)) { dispatch(ControlsBarIntent.Redo) }
        }
    }
}

/**
 * What one button in the rail looks like. Separating this from what the button does keeps the
 * appearance in one value the rail can build per tool, rather than a widening parameter list that
 * every call site has to read positionally.
 */
internal data class Control(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
internal fun ControlButton(control: Control, size: androidx.compose.ui.unit.Dp = BUTTON_SIZE, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = control.enabled,
        modifier = Modifier.size(size).semantics { contentDescription = control.label },
        colors = if (control.selected) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(imageVector = control.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

@Composable
private fun RailDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = LettaDimens.Space.xs)
            .height(1.dp)
            .width(LettaDimens.Space.xl)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

/** The tools that pick and move, in rail order, with the label a click target reads out. */
internal val PointerModes: List<Pair<Mode, String>> = listOf(
    Mode.SELECT to "Select",
    Mode.PAN to "Pan",
)

/** The tools that put something on the board, in rail order. */
internal val DrawingModes: List<Pair<Mode, String>> = listOf(
    Mode.PEN to "Draw",
    Mode.LINE to "Line",
    Mode.ARROW to "Arrow",
    Mode.RECTANGLE to "Rectangle",
    Mode.CIRCLE to "Circle",
    Mode.TRIANGLE to "Triangle",
    // The board's own text element: DrawBox places it, measures it, wraps it and edits it, so
    // text is a thing on the drawing like every other thing rather than a note pretending to be
    // one. See CanvasWorkspace for the editor this mode asks for.
    Mode.TEXT to "Text",
    Mode.ERASER to "Eraser",
)

/** Every drawing mode the rail offers, for callers that iterate them regardless of group. */
internal val CanvasModes: List<Pair<Mode, String>> = PointerModes + DrawingModes

internal fun iconFor(mode: Mode): ImageVector = when (mode) {
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

private val BUTTON_SIZE = LettaDimens.Control.actionButton

/** DrawBox's own starting size for text, for a bar with no state to read yet. */
private const val DEFAULT_FONT_SIZE = 24f
