package com.letta.mobile.ui.icons

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPath
import com.letta.mobile.ui.theme.AgentSphereCore
import com.letta.mobile.ui.theme.AgentSphereMid
import com.letta.mobile.ui.theme.AgentSphereRim
import com.letta.mobile.ui.theme.lettaColorScheme
import com.letta.mobile.ui.theme.lettaCustomColors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Home is drawn as the Baken mark (the kit's two basalt stones on a 200 viewport), text on the vermilion primary
 * reads at 4.5:1 in both themes, vermilion is the only accent (secondary and tertiary are slate and steel greys), and the
 * user's chat bubble is a neutral surface. The oracles are the kit's own numbers, not the production helpers.
 */
class BakenBrandTest {
    @Test
    fun homeIsTheBakenMarkFromTheKit() {
        val home = LettaIcons.Home

        assertEquals("BakenMark", home.name)
        assertEquals(KIT_VIEWPORT, home.viewportWidth)
        assertEquals(KIT_VIEWPORT, home.viewportHeight)
        assertEquals(KIT_PATHS, home.root.filterIsInstance<VectorPath>().size)
    }

    @Test
    fun textOnTheVermilionPrimaryReadsInBothThemes() {
        assertEquals(Color(0xFFEE5A3F), lettaColorScheme(dark = true).primary)
        assertEquals(Color(0xFFE04A32), lettaColorScheme(dark = false).primary)
        for (scheme in listOf(lettaColorScheme(dark = true), lettaColorScheme(dark = false))) {
            assertReads(scheme.onPrimary, scheme.primary, TEXT_CONTRAST, "onPrimary on primary")
            val container = scheme.primaryContainer.compositeOver(scheme.surface)
            assertReads(scheme.onPrimaryContainer, container, TEXT_CONTRAST, "onPrimaryContainer")
        }
    }

    @Test
    fun theSchemeSitsOnTheNorthSeaBuoyBackgrounds() {
        assertEquals(Color(0xFF101214), lettaColorScheme(dark = true).background)
        assertEquals(Color(0xFFF4F5F6), lettaColorScheme(dark = false).background)
        assertEquals(Color(0xFFE8EBEE), lettaColorScheme(dark = true).onSurface)
        assertEquals(Color(0xFF15191D), lettaColorScheme(dark = false).onSurface)
    }

    @Test
    fun secondaryAndTertiaryAreTheKitSlateAndMistNotTealOrCyan() {
        val dark = lettaColorScheme(dark = true)
        val light = lettaColorScheme(dark = false)

        assertEquals(Color(0xFF8C98A3), dark.secondary)
        assertEquals(Color(0xFFA9B3BC), dark.tertiary)
        assertEquals(Color(0xFF4C5761), light.secondary)
        assertEquals(Color(0xFF5A6670), light.tertiary)
        for (scheme in listOf(dark, light)) {
            for ((role, colour) in accentRoles(scheme)) {
                assertFalse(colour.isTealOrCyan(), "$role ${colour.hex()} still reads as teal/cyan")
            }
        }
    }

    @Test
    fun secondaryAndTertiaryInkReadOnTheirSurfacesAndContainers() {
        for (scheme in listOf(lettaColorScheme(dark = true), lettaColorScheme(dark = false))) {
            assertReads(scheme.secondary, scheme.surface, GRAPHIC_CONTRAST, "secondary on surface")
            for ((role, surface) in surfaceRoles(scheme)) {
                assertReads(scheme.tertiary, surface, TEXT_CONTRAST, "tertiary on $role")
            }
            assertReads(scheme.onSecondary, scheme.secondary, TEXT_CONTRAST, "onSecondary on secondary")
            assertReads(scheme.onTertiary, scheme.tertiary, TEXT_CONTRAST, "onTertiary on tertiary")
            assertReads(scheme.onSecondaryContainer, scheme.secondaryContainer, TEXT_CONTRAST, "onSecondaryContainer")
            assertReads(scheme.onTertiaryContainer, scheme.tertiaryContainer, TEXT_CONTRAST, "onTertiaryContainer")
        }
    }

    @Test
    fun theUserBubbleIsANeutralDeepSeaOrMistSurfaceNotVermilion() {
        val darkScheme = lettaColorScheme(dark = true)
        val lightScheme = lettaColorScheme(dark = false)
        val dark = lettaCustomColors(darkScheme)
        val light = lettaCustomColors(lightScheme)

        // A grey step above the agent bubble (dark) and a soft grey (light).
        assertEquals(Color(0xFF23272C), dark.userBubbleBgColor)
        assertEquals(Color(0xFFE4E7EA), light.userBubbleBgColor)
        for ((scheme, colours) in listOf(darkScheme to dark, lightScheme to light)) {
            val bubble = colours.userBubbleBgColor
            // Chroma (max - min channel), not HSL saturation, which inflates for dark colours: the vermilion is ~0.7.
            assertTrue(bubble.chroma() < NEUTRAL_CHROMA, "the user bubble ${bubble.hex()} is not neutral")
            assertNotEquals(scheme.primaryContainer, bubble, "the user bubble still reuses primaryContainer")
            assertNotEquals(colours.agentBubbleBgColor, bubble, "the user bubble must stand apart from the agent's")
            assertReads(scheme.onSurface, bubble, TEXT_CONTRAST, "text on the user bubble")
        }
    }

    @Test
    fun theAssistantSphereIsAVermilionToDeepPetrolRadialNotTeal() {
        // The kit's vermilion at the centre and the petrol stone of the mark at the rim.
        assertEquals(Color(0xFFEE5A3F), AgentSphereMid)
        assertEquals(Color(0xFF163044), AgentSphereRim)
        for ((stop, colour) in listOf("core" to AgentSphereCore, "mid" to AgentSphereMid, "rim" to AgentSphereRim)) {
            assertFalse(colour.isTealOrCyan(), "the sphere's $stop ${colour.hex()} still reads as teal")
        }
    }

    @Test
    fun thePersonaCategoryIsABrandColourNotTeal() {
        for (scheme in listOf(lettaColorScheme(dark = true), lettaColorScheme(dark = false))) {
            val persona = lettaCustomColors(scheme).categoryPersonaColor
            assertFalse(persona.isTealOrCyan(), "persona ${persona.hex()} still reads as teal")
            assertReads(persona, scheme.surface, GRAPHIC_CONTRAST, "persona on surface")
        }
    }

    @Test
    fun theSurfacesAreNeutralGreysWithOnlyAFaintCoolTint() {
        for (scheme in listOf(lettaColorScheme(dark = true), lettaColorScheme(dark = false))) {
            val colours = lettaCustomColors(scheme)
            val roles = listOf(
                "background" to scheme.background,
                "surface" to scheme.surface,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                "secondaryContainer" to scheme.secondaryContainer,
                "outlineVariant" to scheme.outlineVariant,
                "userBubble" to colours.userBubbleBgColor,
            )
            for ((role, colour) in roles) {
                assertTrue(colour.saturation() <= NEUTRAL_SURFACE_SATURATION, "$role ${colour.hex()} reads as a colour, not a grey")
            }
        }
    }

    private fun surfaceRoles(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    private fun accentRoles(scheme: ColorScheme) = listOf(
        "secondary" to scheme.secondary,
        "tertiary" to scheme.tertiary,
        "secondaryContainer" to scheme.secondaryContainer,
        "tertiaryContainer" to scheme.tertiaryContainer,
    )

    private fun assertReads(ink: Color, fill: Color, minimum: Double, what: String) {
        val ratio = contrast(ink, fill)
        assertTrue(ratio >= minimum, "$what: ${ink.hex()} on ${fill.hex()} is ${"%.2f".format(ratio)}:1, below $minimum:1")
    }

    private fun Color.hex() = "#%06X".format(toArgb() and RGB_MASK)

    private fun Color.channels(): List<Double> = listOf(red, green, blue).map(Float::toDouble)

    private fun Color.chroma(): Double = channels().let { it.max() - it.min() }

    private fun Color.saturation(): Double {
        val (max, min) = channels().let { it.max() to it.min() }
        val lightness = (max + min) / 2
        return if (max == min) 0.0 else (max - min) / (1 - abs(2 * lightness - 1))
    }

    private fun Color.hue(): Double {
        val (r, g, b) = channels()
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0.0) return 0.0
        val sector = when (max) {
            r -> ((g - b) / delta).mod(HUE_SECTORS)
            g -> (b - r) / delta + 2
            else -> (r - g) / delta + 4
        }
        return sector * DEGREES_PER_SECTOR
    }

    private fun Color.isTealOrCyan(): Boolean = hue() in TEAL_HUES && saturation() > VIVID_SATURATION

    /** WCAG 2 contrast ratio, computed here from sRGB rather than through a production helper. */
    private fun contrast(a: Color, b: Color): Double {
        fun linear(c: Double) = if (c <= SRGB_KNEE) c / SRGB_SLOPE else ((c + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
        fun luminance(c: Color) = c.channels().map(::linear).let { (r, g, b) -> LUMA_R * r + LUMA_G * g + LUMA_B * b }
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + FLARE) / (lo + FLARE)
    }

    private companion object {
        const val KIT_VIEWPORT = 200f
        const val KIT_PATHS = 2
        const val TEXT_CONTRAST = 4.5
        const val GRAPHIC_CONTRAST = 3.0
        const val NEUTRAL_CHROMA = 0.15
        const val NEUTRAL_SURFACE_SATURATION = 0.16
        const val VIVID_SATURATION = 0.35
        val TEAL_HUES = 150.0..200.0
        const val HUE_SECTORS = 6.0
        const val DEGREES_PER_SECTOR = 60.0
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
