package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BringToFront
import com.composables.icons.lucide.Copy
import kotlin.math.roundToInt
import com.composables.icons.lucide.AArrowDown
import com.composables.icons.lucide.AArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.SendToBack
import com.composables.icons.lucide.TextCursorInput
import com.composables.icons.lucide.Trash2
import com.letta.mobile.data.canvas.CanvasTextStyle
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState
import com.letta.mobile.ui.theme.LettaDimens

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
    /** The selected shape's text while it is being edited, for the property panel's Text target. */
    shapeText: NoteBarActions? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.md),
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
            CanvasPropertyControl(
                state = state,
                properties = properties,
                dispatch = dispatch,
                dispatchProperty = dispatchProperty,
                note = note,
                shapeText = shapeText,
                modifier = Modifier.size(BAR_BUTTON),
            )
            if (note != null) {
                Divider()
                BarButton(Lucide.Maximize2, "Open note large", onClick = note.onOpen)
                onDuplicate?.let { BarButton(Lucide.Copy, "Duplicate note", onClick = it) }
                BarButton(Lucide.Trash2, "Delete note", onClick = note.onDelete)
            } else if (hasSelection) {
                onEditText?.let {
                    Divider()
                    BarButton(Lucide.TextCursorInput, "Edit text", onClick = it)
                }
                // Text is sized from the bar it is selected on, not from a panel behind a swatch:
                // it is the one property you reach for over and over, and a step up or a step down
                // is the whole of what that needs.
                if (properties.showFontSize) {
                    Divider()
                    BarButton(Lucide.AArrowDown, "Smaller text") {
                        dispatchProperty(CanvasPropertyIntent.SetFontSize(steppedFontSize(properties.fontSize, up = false)))
                    }
                    BarButton(Lucide.AArrowUp, "Larger text") {
                        dispatchProperty(CanvasPropertyIntent.SetFontSize(steppedFontSize(properties.fontSize, up = true)))
                    }
                }
                Divider()
                BarButton(Lucide.BringToFront, "Bring to front", onClick = onBringToFront)
                BarButton(Lucide.SendToBack, "Send to back", onClick = onSendToBack)
                Divider()
                onDuplicate?.let { BarButton(Lucide.Copy, "Duplicate selection", onClick = it) }
                BarButton(Lucide.Trash2, "Delete selection", onClick = onDelete)
            }
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
private fun BarButton(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(BAR_BUTTON).semantics { contentDescription = label },
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .padding(horizontal = LettaDimens.Space.hair)
            .width(1.dp)
            .height(LettaDimens.Space.xl)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

private val BAR_BUTTON = LettaDimens.Space.xxl
