@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.HighContrastPreference
import com.letta.mobile.ui.theme.LocalHighContrast
import com.letta.mobile.ui.theme.LocalHighContrastPreference
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel's border language, drawn. A panel is borderless by default: against a page of its own fill
 * (the tonal step is what separates it) it paints nothing but that fill. The hairline [lettaPanelBorder]
 * draws when a panel opts in is a whisper in normal contrast — barely-there, per the visual language — and
 * keeps full strength when the person or the OS asked for high contrast, so a high-contrast reader never
 * loses the edge. The high-contrast render goes through the real preference and policy, so the scheme's
 * own contrast boost and the stroke's strength are both in what is measured.
 */
class LettaPanelBorderContrastTest {

    /** A default panel paints its fill and nothing else: no pixel of it differs from the fill. */
    @Test
    fun aDefaultPanelIsItsOwnFillOnly() = panelOnItsOwnFill { image, fill ->
        assertEquals(0, image.pixelsOutside(fill, tolerance = 1), "a default panel draws no border")
    }

    /** The opted-in hairline draws, but as a whisper: no pixel leaves the fill by more than a hair. */
    @Test
    fun theOptedInHairlineIsAWhisper() = panelOnItsOwnFill(border = true) { image, fill ->
        assertTrue(image.pixelsOutside(fill, tolerance = 1) > 0, "the opted-in hairline draws")
        assertTrue(image.maxDeviationFrom(fill) <= WHISPER, "the hairline is extremely low contrast")
    }

    /** High contrast keeps the hairline strong: far outside the whisper the normal line stays inside. */
    @Test
    fun highContrastDrawsAFullStrengthHairline() = panelOnItsOwnFill(border = true, highContrast = true) { image, fill ->
        assertTrue(image.maxDeviationFrom(fill) > STRONG, "high contrast draws a strong hairline")
    }

    /**
     * The stroke's own contract, independent of rasterisation: a hairline wide as always, carrying a
     * quarter of `outlineVariant` in normal contrast and all of the (theme-boosted) role in high contrast.
     */
    @Test
    fun theHairlineIsAQuarterOfOutlineVariantFullStrengthInHighContrast() =
        runDesktopComposeUiTest(WINDOW, WINDOW) {
            var stroke: BorderStroke? = null
            var boostedStroke: BorderStroke? = null
            var outlineVariant = Color.Unspecified
            setContent {
                SharedMaterialTheme(darkTheme = true) {
                    outlineVariant = MaterialTheme.colorScheme.outlineVariant
                    stroke = lettaPanelBorder()
                    CompositionLocalProvider(LocalHighContrast provides true) {
                        boostedStroke = lettaPanelBorder()
                    }
                }
            }
            waitForIdle()
            assertEquals(LettaStrokeTokens.hairline, stroke!!.width)
            assertEquals(
                outlineVariant.copy(alpha = PANEL_BORDER_ALPHA),
                (stroke!!.brush as SolidColor).value,
                "the normal hairline is a whisper of outlineVariant",
            )
            assertEquals(outlineVariant, (boostedStroke!!.brush as SolidColor).value, "high contrast draws the full role")
        }

    /**
     * Renders a panel on a page of its own fill (the default tone's fill on the same fill: only a border
     * can show) and hands the captured panel with that fill to [check]. [border] null means the call leaves
     * the parameter out, so what is rendered is the default under test.
     */
    private fun panelOnItsOwnFill(
        border: Boolean? = null,
        highContrast: Boolean = false,
        check: (BufferedImage, Int) -> Unit,
    ) = runDesktopComposeUiTest(WINDOW, WINDOW) {
        var fill = 0
        val panelModifier = Modifier.size(PANEL_WIDTH, PANEL_HEIGHT).testTag(PANEL)
        setContent {
            val theme: @Composable () -> Unit = {
                SharedMaterialTheme(darkTheme = true) {
                    fill = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
                        if (border == null) {
                            LettaPanel(modifier = panelModifier) {}
                        } else {
                            LettaPanel(modifier = panelModifier, border = border) {}
                        }
                    }
                }
            }
            if (highContrast) {
                CompositionLocalProvider(LocalHighContrastPreference provides HighContrastPreference().apply { set(true) }) { theme() }
            } else {
                theme()
            }
        }
        waitForIdle()
        check(onNodeWithTag(PANEL).captureToImage().toAwtImage(), fill)
    }

    /** How many of the image's pixels differ from [fill] in any channel by more than [tolerance]. */
    private fun BufferedImage.pixelsOutside(fill: Int, tolerance: Int): Int {
        var outside = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (deviation(getRGB(x, y), fill) > tolerance) outside++
            }
        }
        return outside
    }

    /** The largest single-channel distance of any pixel from [fill]. */
    private fun BufferedImage.maxDeviationFrom(fill: Int): Int {
        var max = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                max = maxOf(max, deviation(getRGB(x, y), fill))
            }
        }
        return max
    }

    private fun deviation(pixel: Int, fill: Int): Int = maxOf(
        abs(((pixel shr 16) and 0xFF) - ((fill shr 16) and 0xFF)),
        maxOf(
            abs(((pixel shr 8) and 0xFF) - ((fill shr 8) and 0xFF)),
            abs((pixel and 0xFF) - (fill and 0xFF)),
        ),
    )

    private companion object {
        /** The hairline the panel language settled on: a quarter of outlineVariant. */
        const val PANEL_BORDER_ALPHA = 0.28f

        /** The whisper's ceiling: the normal hairline never leaves its fill by more than this. */
        const val WHISPER = 8

        /** What a high-contrast hairline clears by a wide margin (its boosted outline is ~110 away). */
        const val STRONG = 40

        const val WINDOW = 400
        val PANEL_WIDTH = 200.dp
        val PANEL_HEIGHT = 100.dp
        const val PANEL = "panel"
    }
}
