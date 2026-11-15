package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaColorSwatch
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaSwatch
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSectionLabel
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import io.ak1.drawbox.domain.model.StrokeStyle
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The board's one property control, in the way Concepts keeps a single master control that
 * always works on whatever is selected. One button opens one popover; the popover shows only the
 * properties that apply to its target, and every colour in it goes through the one
 * [CanvasColorPicker].
 *
 * The target, in priority: the DrawBox selection, else the active note or text element, else the
 * tool about to draw. [note] is non-null for the note/text case; otherwise [state] and
 * [properties] describe the drawing selection or the tool defaults, and changes go out as
 * [ControlsBarIntent]s (colour, outline) and [CanvasPropertyIntent]s (width, opacity, dash,
 * radius) for [CanvasControlsBridge] to apply to the selection or the tool.
 *
 * [placement] puts the popover below the opener, to its right (for the tool rail), or above it
 * (for the phone's bottom tool bar).
 */
@Composable
fun CanvasPropertyControl(
    state: ControlsBarState,
    properties: CanvasProperties,
    dispatch: (ControlsBarIntent) -> Unit,
    dispatchProperty: (CanvasPropertyIntent) -> Unit,
    modifier: Modifier = Modifier,
    note: NoteBarActions? = null,
    /** The selected shape's text: offered as a Text target beside stroke and fill. */
    shapeText: ShapeTextActions? = null,
    label: String = "Properties",
    placement: PropertyPopoverPlacement = PropertyPopoverPlacement.BELOW,
) {
    var open by remember { mutableStateOf(false) }
    val swatch = when {
        note == null -> state.strokeColor
        note.plain -> parseHexColor(note.style?.textColor) ?: note.defaultTextColor
        else -> note.color
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = !open }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            // The swatch shows the colour in force; the sliders glyph over it says it opens every property.
            LettaColorSwatch(LettaSwatch(swatch, size = OPENER_SIZE))
            if (swatch.alpha != 0f) {
                val glyph = LettaIconStyle(OPENER_SIZE * OPENER_GLYPH_RATIO, contrastOn(swatch))
                LettaIcon(LettaIcons.SlidersHorizontal, contentDescription = null, style = glyph)
            }
        }
        if (open) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { (OPENER_SIZE + LettaDimens.Space.md).roundToPx() }
            Popup(
                alignment = when (placement) {
                    PropertyPopoverPlacement.BELOW -> Alignment.TopCenter
                    PropertyPopoverPlacement.BESIDE -> Alignment.TopStart
                    PropertyPopoverPlacement.ABOVE -> Alignment.BottomCenter
                },
                offset = when (placement) {
                    PropertyPopoverPlacement.BELOW -> IntOffset(0, gap)
                    PropertyPopoverPlacement.BESIDE -> IntOffset(gap, 0)
                    PropertyPopoverPlacement.ABOVE -> IntOffset(0, -gap)
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CanvasPropertyPanel(
                    state = state,
                    properties = properties,
                    dispatch = dispatch,
                    dispatchProperty = dispatchProperty,
                    note = note,
                    shapeText = shapeText,
                    onClose = { open = false },
                )
            }
        }
    }
}

/** Where [CanvasPropertyControl] opens its panel relative to the opener. */
enum class PropertyPopoverPlacement { BELOW, BESIDE, ABOVE }

/** Which colour of the target the embedded picker edits. */
private enum class ColorTarget(val label: String, val glyph: ImageVector) {
    STROKE("stroke", LettaIcons.PenLine),
    FILL("fill", LettaIcons.PaintBucket),
    TEXT("text", LettaIcons.Type),
    CARD("card", LettaIcons.StickyNote),
}

@Composable
private fun CanvasPropertyPanel(
    state: ControlsBarState,
    properties: CanvasProperties,
    dispatch: (ControlsBarIntent) -> Unit,
    dispatchProperty: (CanvasPropertyIntent) -> Unit,
    note: NoteBarActions?,
    shapeText: ShapeTextActions?,
    onClose: () -> Unit,
) {
    val targets = when {
        note == null && state.showFillTarget -> listOf(ColorTarget.STROKE, ColorTarget.FILL)
        note == null -> listOf(ColorTarget.STROKE)
        note.plain -> listOf(ColorTarget.TEXT)
        else -> listOf(ColorTarget.TEXT, ColorTarget.CARD)
    } + if (note == null && shapeText != null) listOf(ColorTarget.TEXT) else emptyList()
    // The Text target is the note's text, else the selected shape's.
    val textOwner = note
    var target by remember(targets) { mutableStateOf(targets.first()) }
    val recent = rememberRecentColors()
    // On a phone the panel opens short - the colours and the one size you reach for - and the rest
    // waits behind "More options", so it never covers the thing being edited.
    val compact = LocalCanvasCompact.current
    var more by remember { mutableStateOf(false) }
    val full = !compact || more
    val style = textOwner?.style ?: CanvasTextStyle()
    val current = when (target) {
        ColorTarget.STROKE -> state.strokeColor
        ColorTarget.FILL -> state.fillColor ?: Color.Transparent
        ColorTarget.TEXT -> shapeText?.color ?: parseHexColor(style.textColor) ?: textOwner?.defaultTextColor ?: Color.Black
        ColorTarget.CARD -> note?.color ?: Color.Transparent
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = LettaDimens.Space.sm,
        modifier = Modifier
            .semantics { contentDescription = "Property panel" }
            .canvasChrome(LocalCanvasChromeRegions.current),
    ) {
        Column(
            modifier = Modifier
                .width(if (compact) COMPACT_PROPERTY_PANEL_WIDTH else PROPERTY_PANEL_WIDTH)
                .heightIn(max = if (compact) COMPACT_PROPERTY_PANEL_MAX_HEIGHT else androidx.compose.ui.unit.Dp.Infinity)
                .verticalScroll(rememberScrollState())
                .padding(if (compact) LettaDimens.Space.sm else LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        note == null && properties.selectionCount > 0 -> "Selection"
                        note == null -> "Tool"
                        note.plain -> "Text"
                        else -> "Note"
                    },
                    style = LettaTextStyles.labelSemibold,
                    modifier = Modifier.weight(1f),
                )
                val close = LettaButtonIcon(LettaIcons.Close, "Close properties", glyph = LettaIconStyle(LettaDimens.Control.iconSm))
                LettaIconButton(close, onClick = onClose)
            }

            // Colour: pick which colour of the target, then the one picker edits it.
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                targets.forEach { t ->
                    val color = when (t) {
                        ColorTarget.STROKE -> state.strokeColor
                        ColorTarget.FILL -> state.fillColor ?: Color.Transparent
                        ColorTarget.TEXT -> shapeText?.color ?: parseHexColor(style.textColor) ?: textOwner?.defaultTextColor ?: Color.Black
                        ColorTarget.CARD -> note?.color ?: Color.Transparent
                    }
                    TargetChip(target = t, color = color, selected = t == target) { target = t }
                }
                if (note == null && state.showFillTarget) {
                    Box(modifier = Modifier.weight(1f))
                    Toggle(
                        label = if (state.strokeEnabled) "Outline on" else "Outline off",
                        icon = LettaIcons.PenLine,
                        selected = state.strokeEnabled,
                    ) { dispatch(ControlsBarIntent.SetStrokeEnabled(!state.strokeEnabled)) }
                }
            }
            CanvasColorPicker(
                current = current,
                palette = when (target) {
                    ColorTarget.CARD -> NoteColors
                    else -> StrokePalette
                },
                allowNone = target == ColorTarget.FILL,
                onPick = { color, done ->
                    when (target) {
                        ColorTarget.STROKE -> dispatch(ControlsBarIntent.SetStrokeColor(color))
                        ColorTarget.FILL -> dispatch(ControlsBarIntent.SetFillColor(color))
                        ColorTarget.TEXT -> if (shapeText != null) shapeText.onColor(color) else textOwner?.onStyle(style.copy(textColor = color.toHex()))
                        ColorTarget.CARD -> note?.onColor(color)
                    }
                    if (done) recent.remember(color)
                },
                flat = true,
                showCustom = full,
            )

            when {
                // A shape with text: the settings below follow the chosen colour target, like tabs,
                // so the panel is the shape's or its text's and never both at once.
                note == null && full && target == ColorTarget.TEXT && shapeText != null -> ShapeTextProperties(shapeText)
                note == null && full -> DrawingProperties(properties = properties, dispatchProperty = dispatchProperty)
                note == null -> ShortDrawingProperties(properties = properties, dispatchProperty = dispatchProperty)
                full -> NoteProperties(note = note, style = style)
            }
            if (!full) {
                TextButton(onClick = { more = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
                    Text("More options", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** The phone's short form: the size you reach for most, nothing else. */
@Composable
private fun ShortDrawingProperties(properties: CanvasProperties, dispatchProperty: (CanvasPropertyIntent) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (properties.showFontSize) {
            FontSizes.forEach { (label, size) ->
                Chip(label = label, description = "Text size $label", selected = properties.fontSize == size) {
                    dispatchProperty(CanvasPropertyIntent.SetFontSize(size))
                }
            }
        } else {
            StrokeWidths.forEach { (label, width) ->
                Chip(label = label, description = "Width $label", selected = properties.strokeWidth == width) {
                    dispatchProperty(CanvasPropertyIntent.SetStrokeWidth(width))
                }
            }
        }
    }
}

@Composable
private fun DrawingProperties(properties: CanvasProperties, dispatchProperty: (CanvasPropertyIntent) -> Unit) {
    // Text is set in a size, not drawn in a stroke width, so this is offered to text alone - for
    // the selected text, or for the next piece the text tool places.
    if (properties.showFontSize) {
        SectionLabel("Text size")
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            FontSizes.forEach { (label, size) ->
                Chip(label = label, description = "Text size $label", selected = properties.fontSize == size) {
                    dispatchProperty(CanvasPropertyIntent.SetFontSize(size))
                }
            }
        }
        SectionLabel("Font")
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            FontFamilies.forEach { (label, key) ->
                Chip(label = label, description = "Font $label", selected = properties.fontFamily == key) {
                    dispatchProperty(CanvasPropertyIntent.SetFontFamily(key))
                }
            }
        }
        SectionLabel("Alignment")
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            TextAlignments.forEach { (label, alignment) ->
                Chip(label = label, description = "Align $label", selected = properties.textAlignment == alignment) {
                    dispatchProperty(CanvasPropertyIntent.SetTextAlignment(alignment))
                }
            }
        }
    }
    SectionLabel("Stroke width")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        StrokeWidths.forEach { (label, width) ->
            Chip(label = label, description = "Width $label", selected = properties.strokeWidth == width) {
                dispatchProperty(CanvasPropertyIntent.SetStrokeWidth(width))
            }
        }
    }
    LabelledSlider(
        label = "Opacity",
        value = properties.opacity,
        range = 0.05f..1f,
        onChange = { dispatchProperty(CanvasPropertyIntent.SetOpacity(it)) },
    )
    SectionLabel("Stroke style")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        StrokeStyles.forEach { (label, style) ->
            Chip(label = label, description = "Stroke $label", selected = properties.strokeStyle == style) {
                dispatchProperty(CanvasPropertyIntent.SetStrokeStyle(style))
            }
        }
    }
    if (properties.showCornerRadius) {
        LabelledSlider(
            label = "Corner radius",
            value = properties.cornerRadius,
            range = 0f..MAX_CORNER_RADIUS,
            onChange = { dispatchProperty(CanvasPropertyIntent.SetCornerRadius(it)) },
        )
    }
}

/** What the Text target of a selected shape shows and sets: the shape's own text settings. */
class ShapeTextActions(
    val color: Color,
    val onColor: (Color) -> Unit,
    val fontSize: Float,
    val onFontSize: (Float) -> Unit,
    val fontFamily: String,
    val onFontFamily: (String) -> Unit,
    val alignment: io.ak1.drawbox.domain.model.TextAlignment,
    val onAlignment: (io.ak1.drawbox.domain.model.TextAlignment) -> Unit,
)

@Composable
private fun ShapeTextProperties(text: ShapeTextActions) {
    SectionLabel("Text size")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        FontSizes.forEach { (label, size) ->
            Chip(label = label, description = "Text size $label", selected = text.fontSize == size) { text.onFontSize(size) }
        }
    }
    SectionLabel("Font")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        FontFamilies.forEach { (label, key) ->
            Chip(label = label, description = "Font $label", selected = text.fontFamily == key) { text.onFontFamily(key) }
        }
    }
    SectionLabel("Alignment")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Toggle("Align start", LettaIcons.AlignLeft, selected = text.alignment == io.ak1.drawbox.domain.model.TextAlignment.LEFT) {
            text.onAlignment(io.ak1.drawbox.domain.model.TextAlignment.LEFT)
        }
        Toggle("Align center", LettaIcons.AlignCenter, selected = text.alignment == io.ak1.drawbox.domain.model.TextAlignment.CENTER) {
            text.onAlignment(io.ak1.drawbox.domain.model.TextAlignment.CENTER)
        }
        Toggle("Align end", LettaIcons.AlignRight, selected = text.alignment == io.ak1.drawbox.domain.model.TextAlignment.RIGHT) {
            text.onAlignment(io.ak1.drawbox.domain.model.TextAlignment.RIGHT)
        }
    }
}

@Composable
private fun NoteProperties(note: NoteBarActions, style: CanvasTextStyle) {
    SectionLabel("Size")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        TextSizes.forEach { (label, scale) ->
            Chip(label = label, description = "Size $label", selected = (style.fontScale ?: 1f) == scale) {
                note.onStyle(style.copy(fontScale = scale))
            }
        }
    }
    SectionLabel("Font")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        TextFamilies.forEach { (label, key) ->
            Chip(label = label, description = "Font $label", selected = (style.fontFamily ?: "sans") == key) {
                note.onStyle(style.copy(fontFamily = key))
            }
        }
    }
    SectionLabel("Alignment")
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        val alignStart = (style.align ?: "start") == "start"
        Toggle("Align start", LettaIcons.AlignLeft, selected = alignStart) { note.onStyle(style.copy(align = "start")) }
        Toggle("Align center", LettaIcons.AlignCenter, selected = style.align == "center") { note.onStyle(style.copy(align = "center")) }
        Toggle("Align end", LettaIcons.AlignRight, selected = style.align == "end") { note.onStyle(style.copy(align = "end")) }
    }
}

@Composable
private fun TargetChip(target: ColorTarget, color: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { LettaIcon(target.glyph, contentDescription = null, style = LettaIconStyle(LettaDimens.Control.iconSm)) },
        leadingIcon = { LettaColorSwatch(LettaSwatch(color, size = LettaDimens.Control.icon)) },
        modifier = Modifier.semantics { contentDescription = "Target ${target.label}" },
    )
}

@Composable
private fun Chip(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Toggle(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(CHIP_HEIGHT).semantics { contentDescription = label },
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        LettaIcon(icon, contentDescription = null)
    }
}

@Composable
private fun SectionLabel(text: String) {
    // The shared section label, flush with the controls it names.
    LettaSectionLabel(text, contentPadding = PaddingValues())
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    SectionLabel(label)
    Slider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier.fillMaxWidth().height(LettaDimens.Space.xl).semantics { contentDescription = label },
    )
}

/** Stroke widths as the control labels them, in world units. */
/** The sizes text is offered in, in points, small to large. */
internal val FontSizes: List<Pair<String, Float>> = listOf(
    "S" to 16f,
    "M" to 24f,
    "L" to 36f,
    "XL" to 56f,
    "XXL" to 80f,
)

/** The faces text can be set in, as DrawBox names them. */
internal val FontFamilies: List<Pair<String, String>> = listOf(
    "Sans" to io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys.SANS,
    "Serif" to io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys.SERIF,
    "Mono" to io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys.MONO,
)

/** Which edge the lines are set against. */
internal val TextAlignments: List<Pair<String, io.ak1.drawbox.domain.model.TextAlignment>> = listOf(
    "left" to io.ak1.drawbox.domain.model.TextAlignment.LEFT,
    "center" to io.ak1.drawbox.domain.model.TextAlignment.CENTER,
    "right" to io.ak1.drawbox.domain.model.TextAlignment.RIGHT,
)

internal val StrokeWidths: List<Pair<String, Float>> = listOf("Thin" to 2f, "Medium" to 4f, "Thick" to 8f, "Bold" to 14f)

/** Stroke styles as the control labels them. */
internal val StrokeStyles: List<Pair<String, StrokeStyle>> = listOf(
    "Solid" to StrokeStyle.SOLID,
    "Dashed" to StrokeStyle.DASHED,
    "Dotted" to StrokeStyle.DOTTED,
)

private const val MAX_CORNER_RADIUS = 64f
private val OPENER_SIZE = LettaDimens.Space.xl

/** The sliders glyph fills this much of the opener's swatch. */
private const val OPENER_GLYPH_RATIO = 0.55f
private val CHIP_HEIGHT = LettaDimens.Control.fieldHeight
