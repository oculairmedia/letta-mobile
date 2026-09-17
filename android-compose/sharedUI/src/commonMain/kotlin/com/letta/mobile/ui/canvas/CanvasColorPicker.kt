package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Lucide

/** One colour a picker offers, with the name its swatch reads out. */
data class NamedColor(val color: Color, val name: String) {
    /** `#rrggbb`, the form the scene stores. */
    val hex: String
        get() {
            val argb = color.toArgb()
            return "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        }
}

/**
 * A swatch showing [current] that opens a palette popup on tap. [allowNone] adds a "no colour"
 * entry (transparent), for fills and outlines that can be turned off. The popup opens above the
 * swatch; [glyph] draws over the swatch so a fill and a stroke swatch tell apart.
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
                ColorPalette(current = current, palette = palette, allowNone = allowNone) {
                    onPick(it)
                    open = false
                }
            }
        }
    }
}

/** The palette itself, for hosts that place it themselves. */
@Composable
fun ColorPalette(
    current: Color,
    palette: List<NamedColor>,
    allowNone: Boolean,
    onPick: (Color) -> Unit,
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
            if (allowNone) {
                PaletteEntry(color = Color.Transparent, name = "none", selected = current.alpha == 0f) { onPick(Color.Transparent) }
            }
            palette.forEach { entry ->
                PaletteEntry(color = entry.color, name = entry.name, selected = entry.color == current) { onPick(entry.color) }
            }
        }
    }
}

@Composable
private fun PaletteEntry(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
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
            Icon(Lucide.Ban, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
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
