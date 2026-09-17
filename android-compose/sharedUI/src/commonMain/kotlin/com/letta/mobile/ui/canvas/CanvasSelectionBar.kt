package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AlignCenter
import com.composables.icons.lucide.AlignLeft
import com.composables.icons.lucide.AlignRight
import com.composables.icons.lucide.BringToFront
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.PaintBucket
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.SendToBack
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Type
import com.letta.mobile.data.canvas.CanvasTextStyle
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The contextual bar at the top of the board, in the way Concepts and Miro show properties for
 * what is selected: stroke colour, fill colour and outline for the selection (or for the tool
 * about to draw a closed shape), then ordering and delete when something is selected.
 *
 * Colour intents go through [dispatch] so [CanvasControlsBridge] applies them to the selection
 * when there is one and to the current tool otherwise. With a [note] active the bar is the
 * note's: open it large, or delete it.
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
    note: NoteBarActions? = null,
) {
    if (note != null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val style = note.style ?: CanvasTextStyle()
                TextSizes.forEach { (label, scale) ->
                    SizeChip(label = label, selected = (style.fontScale ?: 1f) == scale) {
                        note.onStyle(style.copy(fontScale = scale))
                    }
                }
                Divider()
                TextFamilies.forEach { (label, key) ->
                    SizeChip(label = label, selected = (style.fontFamily ?: "sans") == key) {
                        note.onStyle(style.copy(fontFamily = key))
                    }
                }
                Divider()
                BarButton(Lucide.AlignLeft, "Align start", selected = (style.align ?: "start") == "start") { note.onStyle(style.copy(align = "start")) }
                BarButton(Lucide.AlignCenter, "Align center", selected = style.align == "center") { note.onStyle(style.copy(align = "center")) }
                BarButton(Lucide.AlignRight, "Align end", selected = style.align == "end") { note.onStyle(style.copy(align = "end")) }
                Divider()
                ColorSwatchPicker(
                    current = parseHexColor(style.textColor) ?: note.defaultTextColor,
                    palette = StrokePalette,
                    label = "Text color",
                    glyph = Lucide.Type,
                    onPick = { note.onStyle(style.copy(textColor = it.toHex())) },
                    modifier = Modifier.size(BAR_BUTTON),
                )
                if (!note.plain) {
                    ColorSwatchPicker(
                        current = note.color,
                        palette = NoteColors,
                        label = "Note card color",
                        onPick = { note.onColor(it) },
                        modifier = Modifier.size(BAR_BUTTON),
                    )
                }
                Divider()
                BarButton(Lucide.Maximize2, "Open note large", onClick = note.onOpen)
                BarButton(Lucide.Trash2, "Delete note", onClick = note.onDelete)
            }
        }
        return
    }
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

/** Text sizes as the bar labels them, and the scale each applies to the editor's sizes. */
internal val TextSizes: List<Pair<String, Float>> = listOf("S" to 0.85f, "M" to 1f, "L" to 1.4f, "XL" to 2f)

/** Font families the bar offers, label to the key the document stores. */
internal val TextFamilies: List<Pair<String, String>> = listOf("Aa" to "sans", "Serif" to "serif", "Mono" to "mono")

@Composable
private fun SizeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.height(BAR_BUTTON).semantics { contentDescription = "Size $label" },
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
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
