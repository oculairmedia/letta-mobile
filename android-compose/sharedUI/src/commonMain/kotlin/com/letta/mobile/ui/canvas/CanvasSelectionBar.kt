package com.letta.mobile.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import com.letta.mobile.data.canvas.CanvasTextStyle
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState
import com.letta.mobile.ui.components.foundation.LettaColorSwatch
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaSwatch
import com.letta.mobile.ui.components.foundation.LettaVerticalDivider
import com.letta.mobile.ui.host.LettaMenu
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.icons.LettaIcons
import io.ak1.drawbox.domain.model.TextAlignment
import com.letta.mobile.ui.components.foundation.LettaMenuEntry
import com.letta.mobile.ui.components.foundation.LettaMenuItem

/**
 * The contextual bar that floats on the selection, the way Miro shows what applies to it: one master control ([CanvasPropertyControl]) for every colour and property of the
 * target, then ordering and delete for a drawn selection, or open-large and delete for the
 * active note or text element.
 *
 * Colour and outline intents go through [dispatch] and the rest through [dispatchProperty], so
 * [CanvasControlsBridge] applies them to the selection when there is one and to the current tool
 * otherwise. With a [note] active the control edits the note instead.
 */
@Composable
fun CanvasSelectionBar(
    state: ControlsBarState,
    properties: CanvasProperties,
    hasSelection: Boolean,
    dispatch: (ControlsBarIntent) -> Unit,
    dispatchProperty: (CanvasPropertyIntent) -> Unit,
    onBringToFront: () -> Unit,
    onSendToBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    note: NoteBarActions? = null,
    onDuplicate: (() -> Unit)? = null,
    /** Puts the caret in the selected shape; offered only when the selection is one that holds text. */
    onEditText: (() -> Unit)? = null,
    /** The selected shape's text, for the property panel's Text target. */
    shapeText: ShapeTextActions? = null,
    /** Turns the selected box, circle or triangle into another of them; null when none is selected. */
    reshape: ShapeReshapeActions? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = LettaDimens.Space.hair,
        shadowElevation = LettaDimens.Space.sm,
    ) {
        // Scrolls rather than clips when the board is narrower than the bar, as on a phone.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val actions = SelectionActions(
                properties = properties,
                dispatchProperty = dispatchProperty,
                onBringToFront = onBringToFront,
                onSendToBack = onSendToBack,
                onDelete = onDelete,
                onDuplicate = onDuplicate,
                onEditText = onEditText,
                shapeText = shapeText,
                reshape = reshape,
            )
            // A phone keeps the bar to what is reached for while working on a shape - its kind and
            // its text size - and puts the rest behind one menu. Colour and style are the tool
            // bar's properties control, a thumb away at the foot; a second copy here was clutter.
            if (LocalCanvasCompact.current && note == null && hasSelection) {
                PhoneSelectionButtons(actions)
                return@Row
            }
            CanvasPropertyControl(
                state = state,
                properties = properties,
                dispatch = dispatch,
                dispatchProperty = dispatchProperty,
                note = note,
                shapeText = shapeText,
                modifier = Modifier.size(BAR_BUTTON),
            )
            when {
                note != null -> NoteSelectionButtons(note, onDuplicate)
                hasSelection -> ShapeSelectionButtons(actions)
            }
        }
    }
}

@Composable
private fun NoteSelectionButtons(note: NoteBarActions, onDuplicate: (() -> Unit)?) {
    Divider()
    BarButton(LettaIcons.Maximize2, "Open note large", onClick = note.onOpen)
    onDuplicate?.let { BarButton(LettaIcons.Copy, "Duplicate note", onClick = it) }
    BarButton(LettaIcons.Delete, "Delete note", onClick = note.onDelete)
}

/** The desktop's bar for selected shapes: kind, text, stacking, and duplicate or delete. */
@Composable
private fun ShapeSelectionButtons(actions: SelectionActions) {
    actions.reshape?.let { ShapeTypeButton(it) }
    actions.onEditText?.let {
        Divider()
        BarButton(LettaIcons.TextCursorInput, "Edit text", onClick = it)
    }
    // A shape's text, right on the bar the way Miro puts it: colour, a step smaller or larger, and
    // alignment. The panel's Text tab has the rest (font, exact sizes).
    actions.shapeText?.let { text -> ShapeTextButtons(text) }
    // Other text is sized from the bar it is selected on, not from a panel behind a swatch: it is
    // the one property you reach for over and over, and a step up or down is all that needs.
    if (actions.properties.showFontSize && actions.shapeText == null) TextStepButtons(actions)
    Divider()
    BarButton(LettaIcons.BringToFront, "Bring to front", onClick = actions.onBringToFront)
    BarButton(LettaIcons.SendToBack, "Send to back", onClick = actions.onSendToBack)
    Divider()
    actions.onDuplicate?.let { BarButton(LettaIcons.Copy, "Duplicate selection", onClick = it) }
    BarButton(LettaIcons.Delete, "Delete selection", onClick = actions.onDelete)
}

@Composable
private fun TextStepButtons(actions: SelectionActions) {
    val size = actions.properties.fontSize
    Divider()
    BarButton(LettaIcons.AArrowDown, "Smaller text") {
        actions.dispatchProperty(CanvasPropertyIntent.SetFontSize(steppedFontSize(size, up = false)))
    }
    BarButton(LettaIcons.AArrowUp, "Larger text") {
        actions.dispatchProperty(CanvasPropertyIntent.SetFontSize(steppedFontSize(size, up = true)))
    }
}

/** Everything a selection bar can do with the selected shapes, on a phone or not. */
private class SelectionActions(
    val properties: CanvasProperties,
    val dispatchProperty: (CanvasPropertyIntent) -> Unit,
    val onBringToFront: () -> Unit,
    val onSendToBack: () -> Unit,
    val onDelete: () -> Unit,
    val onDuplicate: (() -> Unit)?,
    val onEditText: (() -> Unit)?,
    val shapeText: ShapeTextActions?,
    val reshape: ShapeReshapeActions?,
)

/** The phone's bar: the shape's kind, its text a step smaller or larger, and the rest behind "More". */
@Composable
private fun PhoneSelectionButtons(actions: SelectionActions) {
    actions.reshape?.let { ShapeTypeButton(it) }
    val text = actions.shapeText
    if (text != null || actions.properties.showFontSize) {
        val size = text?.fontSize ?: actions.properties.fontSize
        val setSize: (Float) -> Unit = text?.onFontSize ?: { actions.dispatchProperty(CanvasPropertyIntent.SetFontSize(it)) }
        BarButton(LettaIcons.AArrowDown, "Smaller text") { setSize(steppedFontSize(size, up = false)) }
        BarButton(LettaIcons.AArrowUp, "Larger text") { setSize(steppedFontSize(size, up = true)) }
    }
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(LettaIcons.EllipsisVertical, "More shape actions") { open = true }
        LettaMenu(expanded = open, onDismiss = { open = false }) {
            fun item(icon: ImageVector, label: String, onClick: () -> Unit) = @Composable {
                LettaMenuEntry(LettaMenuItem(label, icon, onClick = onClick), onDismiss = { open = false })
            }
            actions.onEditText?.let { item(LettaIcons.TextCursorInput, "Edit text", it)() }
            if (text != null) {
                DropdownMenuItem(
                    text = { TextColourSwatches(text) },
                    onClick = {},
                )
                val (icon, next) = when (text.alignment) {
                    TextAlignment.LEFT -> LettaIcons.AlignLeft to TextAlignment.CENTER
                    TextAlignment.CENTER -> LettaIcons.AlignCenter to TextAlignment.RIGHT
                    TextAlignment.RIGHT -> LettaIcons.AlignRight to TextAlignment.LEFT
                }
                item(icon, "Text alignment") { text.onAlignment(next) }()
            }
            item(LettaIcons.BringToFront, "Bring to front", actions.onBringToFront)()
            item(LettaIcons.SendToBack, "Send to back", actions.onSendToBack)()
            actions.onDuplicate?.let { item(LettaIcons.Copy, "Duplicate", it)() }
            item(LettaIcons.Delete, "Delete", actions.onDelete)()
        }
    }
}

/**
 * The text colour, as a row of swatches inside the phone bar's menu. Each swatch's touch bounds
 * reach the 48dp minimum around its dot, so the dots sit a whole dot apart and a tap between two
 * never overlaps both targets. The row scrolls when the palette outgrows the menu.
 */
@Composable
private fun TextColourSwatches(text: ShapeTextActions) {
    Row(
        modifier = Modifier
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.md),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StrokePalette.forEach { swatch ->
            val selected = swatch.color.toHex() == text.color.toHex()
            val entry = LettaSwatch(swatch.color, selected, name = "Text colour ${swatch.name}")
            LettaColorSwatch(entry, onClick = { text.onColor(swatch.color) })
        }
    }
}

/** What the bar offers for the active note or text element, and what it knows about it. */
class NoteBarActions(
    val onOpen: () -> Unit,
    val onDelete: () -> Unit,
    val style: CanvasTextStyle?,
    val onStyle: (CanvasTextStyle) -> Unit,
    val color: androidx.compose.ui.graphics.Color,
    val onColor: (androidx.compose.ui.graphics.Color) -> Unit,
    val defaultTextColor: androidx.compose.ui.graphics.Color,
    /** True for a text element (no card), which has no note colour to offer. */
    val plain: Boolean,
)

/**
 * One step up or down from [size], as the bar's two buttons take it.
 *
 * A ratio rather than a ladder: a step is the same visual amount at every size, and text that was
 * set to something the ladder does not name cannot get stuck between rungs. Clamped so a step can
 * never leave text too small to find or too large to fit on a board.
 */
internal fun steppedFontSize(size: Float, up: Boolean): Float {
    val stepped = if (up) size * FONT_STEP else size / FONT_STEP
    return stepped.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE).roundToInt().toFloat()
}

private const val FONT_STEP = 1.25f
private const val MIN_FONT_SIZE = 8f
private const val MAX_FONT_SIZE = 240f

/** Text sizes as the control labels them, and the scale each applies to the editor's sizes. */
internal val TextSizes: List<Pair<String, Float>> = listOf("S" to 0.85f, "M" to 1f, "L" to 1.4f, "XL" to 2f)

/** Font families the control offers, label to the key the document stores. */
internal val TextFamilies: List<Pair<String, String>> = listOf("Sans" to "sans", "Serif" to "serif", "Mono" to "mono")

@Composable
private fun ShapeTextButtons(text: ShapeTextActions) {
    Divider()
    ColorSwatchPicker(
        current = text.color,
        palette = StrokePalette,
        label = "Text color",
        glyph = LettaIcons.Type,
        onPick = text.onColor,
        modifier = Modifier.size(BAR_BUTTON),
    )
    BarButton(LettaIcons.AArrowDown, "Smaller text") { text.onFontSize(steppedFontSize(text.fontSize, up = false)) }
    BarButton(LettaIcons.AArrowUp, "Larger text") { text.onFontSize(steppedFontSize(text.fontSize, up = true)) }
    val (icon, next) = when (text.alignment) {
        TextAlignment.LEFT -> LettaIcons.AlignLeft to TextAlignment.CENTER
        TextAlignment.CENTER -> LettaIcons.AlignCenter to TextAlignment.RIGHT
        TextAlignment.RIGHT -> LettaIcons.AlignRight to TextAlignment.LEFT
    }
    BarButton(icon, "Text alignment") { text.onAlignment(next) }
}

@Composable
private fun ShapeTypeButton(reshape: ShapeReshapeActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(shapeIcon(reshape.current ?: ShapeType.RECTANGLE), "Change shape") { open = true }
        LettaMenu(expanded = open, onDismiss = { open = false }) {
            CanvasReshape.Types.forEach { type ->
                val item = LettaMenuItem(shapeLabel(type), shapeIcon(type), enabled = type != reshape.current) { reshape.onPick(type) }
                LettaMenuEntry(item, onDismiss = { open = false })
            }
        }
    }
}

private fun shapeIcon(type: ShapeType): ImageVector = when (type) {
    ShapeType.CIRCLE -> LettaIcons.Circle
    ShapeType.TRIANGLE -> LettaIcons.Triangle
    else -> LettaIcons.Square
}

private fun shapeLabel(type: ShapeType): String = when (type) {
    ShapeType.CIRCLE -> "Circle"
    ShapeType.TRIANGLE -> "Triangle"
    else -> "Rectangle"
}

@Composable
private fun BarButton(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(BAR_BUTTON).semantics { contentDescription = label },
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        LettaIcon(icon, contentDescription = null)
    }
}

@Composable
private fun Divider() {
    LettaVerticalDivider(Modifier.padding(horizontal = LettaDimens.Space.hair).height(LettaDimens.Space.xl))
}

private val BAR_BUTTON = LettaDimens.Space.xxl
