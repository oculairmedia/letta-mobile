package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Lucide

/** One colour a picker offers, with the name its swatch reads out. */
data class NamedColor(val color: Color, val name: String) {
    /** `#rrggbb`, the form the scene stores. */
    val hex: String get() = color.toHex()
}

/** `#rrggbb` for an opaque colour, `#rrggbbaa` when it is not. */
fun Color.toHex(): String {
    val argb = toArgb()
    val rgb = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
    val a = (argb ushr 24) and 0xFF
    return if (a == 0xFF) rgb else rgb + a.toString(16).padStart(2, '0')
}

/**
 * Colours picked in this session, newest first, so the next control offers them again. One list
 * for the whole workspace: stroke, fill, text, note and background all feed and read it, which is
 * what makes the picker feel central rather than per control.
 */
object RecentColors {
    val colors = mutableStateListOf<Color>()

    fun remember(color: Color) {
        if (color.alpha == 0f) return
        colors.remove(color)
        colors.add(0, color)
        while (colors.size > RECENT_LIMIT) colors.removeAt(colors.size - 1)
    }
}

/**
 * The one colour control of the board: a swatch showing [current] that opens [CanvasColorPicker].
 * [allowNone] adds a "no colour" entry (transparent), for fills that can be turned off. [glyph]
 * draws over the swatch so a fill and a stroke swatch tell apart.
 */
@Composable
fun ColorSwatchPicker(
    current: Color,
    palette: List<NamedColor>,
    label: String,
    onPick: (Color) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
    glyph: ImageVector? = null,
    swatchSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(swatchSize)
                .background(current, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                .semantics { contentDescription = label }
                .clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            if (current.alpha == 0f) {
                Icon(Lucide.Ban, contentDescription = null, modifier = Modifier.size(swatchSize * 0.7f), tint = MaterialTheme.colorScheme.outline)
            } else if (glyph != null) {
                Icon(glyph, contentDescription = null, modifier = Modifier.size(swatchSize * 0.6f), tint = contrastOn(current))
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CanvasColorPicker(
                    current = current,
                    palette = palette,
                    allowNone = allowNone,
                    onPick = { color, done ->
                        onPick(color)
                        if (done) {
                            RecentColors.remember(color)
                            open = false
                        }
                    },
                )
            }
        }
    }
}

/**
 * The picker itself, Concepts-style: preset swatches, the colours picked recently, hue /
 * saturation / lightness sliders, and a hex field. [onPick] is called with `done = true` for a
 * swatch tap (which closes the popup) and `done = false` while a slider or the hex field is being
 * edited, so the board updates live without the popup vanishing mid-drag.
 */
@Composable
fun CanvasColorPicker(
    current: Color,
    palette: List<NamedColor>,
    allowNone: Boolean,
    onPick: (color: Color, done: Boolean) -> Unit,
) {
    var hsl by remember(current) { mutableStateOf(current.toHsl()) }
    var hexText by remember(current) { mutableStateOf(current.toHex()) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp).width(PICKER_WIDTH),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SwatchRow(current = current, entries = palette.map { it.color to it.name }, allowNone = allowNone) { onPick(it, true) }
            if (RecentColors.colors.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwatchRow(
                    current = current,
                    entries = RecentColors.colors.mapIndexed { index, color -> color to "recent ${index + 1}" },
                    allowNone = false,
                ) { onPick(it, true) }
            }
            HslSlider("Hue", hsl.h, 0f..360f) { hsl = hsl.copy(h = it); onPick(hsl.toColor(), false) }
            HslSlider("Saturation", hsl.s, 0f..1f) { hsl = hsl.copy(s = it); onPick(hsl.toColor(), false) }
            HslSlider("Lightness", hsl.l, 0f..1f) { hsl = hsl.copy(l = it); onPick(hsl.toColor(), false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(hsl.toColor(), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                BasicTextField(
                    value = hexText,
                    onValueChange = { typed ->
                        hexText = typed
                        parseHexColor(typed.trim())?.let { color ->
                            hsl = color.toHsl()
                            onPick(color, false)
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Hex color" },
                )
            }
        }
    }
}

@Composable
private fun SwatchRow(current: Color, entries: List<Pair<Color, String>>, allowNone: Boolean, onPick: (Color) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowNone) {
            PaletteEntry(color = Color.Transparent, name = "none", selected = current.alpha == 0f) { onPick(Color.Transparent) }
        }
        entries.forEach { (color, name) ->
            PaletteEntry(color = color, name = name, selected = color == current) { onPick(color) }
        }
    }
}

@Composable
private fun HslSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.take(1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
    }
}

@Composable
private fun PaletteEntry(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .semantics { contentDescription = "Color $name" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color.alpha == 0f) {
            Icon(Lucide.Ban, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Hue in degrees, saturation and lightness in 0..1. */
internal data class Hsl(val h: Float, val s: Float, val l: Float) {
    fun toColor(): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hh = (h % 360f + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when {
            hh < 1f -> Triple(c, x, 0f)
            hh < 2f -> Triple(x, c, 0f)
            hh < 3f -> Triple(0f, c, x)
            hh < 4f -> Triple(0f, x, c)
            hh < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
    }
}

internal fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val l = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        red -> ((green - blue) / d + (if (green < blue) 6f else 0f)) * 60f
        green -> ((blue - red) / d + 2f) * 60f
        else -> ((red - green) / d + 4f) * 60f
    }
    return Hsl(h, s, l)
}

/** Black or white, whichever reads on [background]. */
internal fun contrastOn(background: Color): Color =
    if (background.alpha > 0f && background.luminanceApprox() < 0.5f) Color.White else Color(0xFF1F1F1F)

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** A fixed marker-style palette; the colours whiteboards reach for, plus black and white. */
val StrokePalette: List<NamedColor> = listOf(
    NamedColor(Color(0xFF1F1F1F), "black"),
    NamedColor(Color(0xFF6B7280), "gray"),
    NamedColor(Color(0xFFE5484D), "red"),
    NamedColor(Color(0xFFF59E0B), "orange"),
    NamedColor(Color(0xFFEAB308), "yellow"),
    NamedColor(Color(0xFF22C55E), "green"),
    NamedColor(Color(0xFF3B82F6), "blue"),
    NamedColor(Color(0xFF8B5CF6), "violet"),
    NamedColor(Color(0xFFFFFFFF), "white"),
)

/** Sticky-note tints: pale enough for dark text, distinct enough to sort by. */
val NoteColors: List<NamedColor> = listOf(
    NamedColor(Color(0xFFFDE68A), "yellow"),
    NamedColor(Color(0xFFFBCFE8), "pink"),
    NamedColor(Color(0xFFBBF7D0), "green"),
    NamedColor(Color(0xFFBFDBFE), "blue"),
    NamedColor(Color(0xFFDDD6FE), "violet"),
    NamedColor(Color(0xFFFED7AA), "orange"),
    NamedColor(Color(0xFFE5E7EB), "gray"),
)

/** Parses `#rrggbb` or `#rrggbbaa`; null for anything else. */
fun parseHexColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#")?.takeIf { it.length == 6 || it.length == 8 } ?: return null
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 6) Color(0xFF000000L or value) else Color(((value and 0xFF) shl 24) or (value shr 8))
}

private const val RECENT_LIMIT = 8
private val PICKER_WIDTH = 260.dp
