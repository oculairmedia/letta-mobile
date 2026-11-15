package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes and colours owned by one designsystem component each, named here so the component reads
 * them rather than writing its own numbers. Shared scale values (spacing, controls, radii) come from
 * LettaDimens and `MaterialTheme.shapes`; only what is specific to one component lives here.
 */
object CardGroupTokens {
    /** Corner of a lone, first, last or pressed card in a group: the expressive outer radius. */
    val outerCorner: Dp = 20.dp

    /** Corner where two cards in a group meet, nearly square so the group reads as one block. */
    val innerCorner: Dp = 4.dp
}

/** The full-screen voice dictation overlay. */
object VoiceOverlayTokens {
    /** Lift of the instruction pill above the navigation bar, clear of the composer it covers. */
    val instructionBottomInset: Dp = 96.dp

    /** The audio wave's gradient, top to bottom: the voice palette (yellow, green, red, blue). */
    val wavePalette: List<Color> = listOf(
        Color(0xFFFDDF85),
        Color(0xFFA0D0AF),
        Color(0xFFE25F57),
        Color(0xFF85B1F8),
    )
}

/** Alphas the drawn effects (glows, gradients) share. */
object EffectAlpha {
    /**
     * A glow's outer gradient stop. A glow fades to its own hue at this alpha rather than to
     * `Color.Transparent` (transparent black), so the unpremultiplied gradient does not darken
     * towards its edge.
     */
    const val GLOW_EDGE: Float = 0f
}
