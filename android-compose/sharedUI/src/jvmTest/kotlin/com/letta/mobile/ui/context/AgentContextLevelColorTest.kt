@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.ContextMeterLevel
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context meter's labels are small text (11 and 10 sp), so the Warning level's vermilion reads at 4.5:1 on every
 * surface level of the brand palette, in both themes, while Normal and Critical keep their own roles. The oracle is WCAG's formula computed here, not a production helper.
 */
class AgentContextLevelColorTest {
    @Test
    fun theWarningLevelReadsAsSmallTextOnEverySurfaceInBothThemes() {
        for (dark in listOf(true, false)) {
            val (scheme, colours) = drawn(dark)
            val ink = colours.getValue(ContextMeterLevel.Warning)
            for ((role, surface) in surfaces(scheme)) {
                val ratio = contrast(ink, surface)
                assertTrue(
                    ratio >= TEXT_CONTRAST,
                    "${if (dark) "dark" else "light"} warning ${ink.hex()} on $role ${surface.hex()} is " +
                        "${"%.2f".format(ratio)}:1, below $TEXT_CONTRAST:1",
                )
            }
        }
    }

    @Test
    fun theNormalAndCriticalLevelsKeepTheirRoles() {
        for (dark in listOf(true, false)) {
            val (scheme, colours) = drawn(dark)
            assertEquals(scheme.onSurfaceVariant, colours.getValue(ContextMeterLevel.Normal))
            assertEquals(scheme.error, colours.getValue(ContextMeterLevel.Critical))
        }
    }

    private fun drawn(dark: Boolean): Pair<ColorScheme, Map<ContextMeterLevel, Color>> {
        var scheme: ColorScheme? = null
        var colours: Map<ContextMeterLevel, Color> = emptyMap()
        runComposeUiTest {
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    scheme = MaterialTheme.colorScheme
                    colours = ContextMeterLevel.entries.associateWith { levelColor(it) }
                }
            }
            waitForIdle()
        }
        return requireNotNull(scheme) to colours
    }

    private fun surfaces(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    private fun Color.hex() = "#%06X".format(toArgb() and RGB_MASK)

    private fun contrast(a: Color, b: Color): Double {
        fun linear(c: Float): Double = c.toDouble().let {
            if (it <= SRGB_KNEE) it / SRGB_SLOPE else ((it + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
        }
        fun luminance(c: Color) = LUMA_R * linear(c.red) + LUMA_G * linear(c.green) + LUMA_B * linear(c.blue)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + FLARE) / (lo + FLARE)
    }

    private companion object {
        const val TEXT_CONTRAST = 4.5
        const val RGB_MASK = 0xFFFFFF
        const val SRGB_KNEE = 0.03928
        const val SRGB_SLOPE = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_GAMMA = 2.4
        const val LUMA_R = 0.2126
        const val LUMA_G = 0.7152
        const val LUMA_B = 0.0722
        const val FLARE = 0.05
    }
}
