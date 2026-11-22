package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaColorTokens

/**
 * The Baken mark (letta-mobile-0sb6o): two river-worn basalt stones, the leaning stone and the pebble, as drawn by the
 * brand kit's flat marks (`svg/baken-mark-{dark,light,mono-*}.svg`, a 200x200 viewport resting on y=168).
 *
 * [mono] is one ink for both stones so an `Icon` tint recolours it (a selection-tinted slot such as the rail's Home).
 * [brand] is the two-colour mark of a brand spot: the pale (dark theme) or petrol (light theme) stone and the vermilion
 * pebble, both read from the design tokens. Draw it with `tint = Color.Unspecified` so its own colours show.
 */
object BakenMark {
    val mono: ImageVector by lazy { build(MONO_NAME, Color.Black, Color.Black) }

    private val brandDark: ImageVector by lazy {
        build(BRAND_DARK_NAME, Color(LettaColorTokens.DARK_MARK_STONE), Color(LettaColorTokens.DARK_PRIMARY))
    }

    private val brandLight: ImageVector by lazy {
        build(BRAND_LIGHT_NAME, Color(LettaColorTokens.LIGHT_MARK_STONE), Color(LettaColorTokens.LIGHT_PRIMARY))
    }

    /** The two-colour mark for a [dark] or light surface. */
    fun brand(dark: Boolean): ImageVector = if (dark) brandDark else brandLight

    private fun build(name: String, stone: Color, pebble: Color): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = DEFAULT_SIZE.dp,
            defaultHeight = DEFAULT_SIZE.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            addPath(pathData = addPathNodes(STONE_PATH), fill = SolidColor(stone))
            addPath(pathData = addPathNodes(PEBBLE_PATH), fill = SolidColor(pebble))
        }.build()

    private const val MONO_NAME = "BakenMark"
    private const val BRAND_DARK_NAME = "BakenMarkDark"
    private const val BRAND_LIGHT_NAME = "BakenMarkLight"
    private const val VIEWPORT = 200f
    private const val DEFAULT_SIZE = 24f

    private const val STONE_PATH =
        "M 33.05 45.11 C 35.20 32.93, 52.02 29.75, 63.34 33.97 C 73.67 38.26, 80.33 61.85, 83.54 107.74 " +
            "C 86.19 145.65, 84.52 164.81, 68.70 167.92 C 49.82 170.25, 36.50 166.16, 34.11 146.28 " +
            "C 30.46 108.45, 30.96 58.29, 33.05 45.11 Z"
    private const val PEBBLE_PATH =
        "M 90.98 132.84 C 93.79 106.97, 110.87 92.13, 133.75 94.73 C 155.62 97.26, 166.55 113.06, 162.74 138.86 " +
            "C 159.14 161.66, 143.55 169.60, 119.61 167.92 C 97.66 166.39, 88.38 155.71, 90.98 132.84 Z"
}
