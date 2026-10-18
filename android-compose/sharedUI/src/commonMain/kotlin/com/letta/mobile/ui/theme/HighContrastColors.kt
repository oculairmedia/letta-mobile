package com.letta.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Stronger on-surface / outline tokens when the OS (or the Settings toggle) asks for high contrast.
 * Accents stay brand-coloured; only the roles that carry text and borders move.
 */
fun ColorScheme.withHighContrast(): ColorScheme {
    val light = background.luminance() > LIGHT_BACKGROUND
    return copy(
        onSurface = if (light) Color.Black else Color.White,
        onBackground = if (light) Color.Black else Color.White,
        onSurfaceVariant = onSurface.boostVariant(light),
        outline = outline.boostOutline(light),
        outlineVariant = outlineVariant.boostOutline(light),
    )
}

private fun Color.boostVariant(light: Boolean): Color =
    if (light) copy(alpha = 1f).mixToward(Color.Black, VARIANT_MIX) else copy(alpha = 1f).mixToward(Color.White, VARIANT_MIX)

private fun Color.boostOutline(light: Boolean): Color =
    if (light) mixToward(Color.Black, OUTLINE_MIX) else mixToward(Color.White, OUTLINE_MIX)

private fun Color.mixToward(target: Color, amount: Float): Color = Color(
    red = red + (target.red - red) * amount,
    green = green + (target.green - green) * amount,
    blue = blue + (target.blue - blue) * amount,
    alpha = alpha,
)

private const val LIGHT_BACKGROUND = 0.5f
private const val VARIANT_MIX = 0.35f
private const val OUTLINE_MIX = 0.45f
