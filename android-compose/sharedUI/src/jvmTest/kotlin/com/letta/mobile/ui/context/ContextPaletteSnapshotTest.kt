@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.ContextWindowSegment
import com.letta.mobile.data.context.ContextWindowSegmentKind
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.ui.theme.LettaColorTokens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.tokens.LettaContextPalette
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The context breakdown's section colours hold 3:1 against their theme's surface (a graphic's minimum),
 * and the bar and legend render in both themes at the phone, tablet and desktop widths, to
 * build/context-palette-snapshots.
 */
class ContextPaletteSnapshotTest {
    private fun LettaContextPalette.all() = listOf(
        system, toolDefinitions, toolRules, coreMemory, memoryFiles,
        directories, summaryMemory, externalSummary, messages, unitemised,
    )

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun everySectionColourReadsOnItsThemesSurfaces() {
        val light = listOf(LettaColorTokens.LIGHT_SURFACE, LettaColorTokens.LIGHT_BACKGROUND, LettaColorTokens.LIGHT_SURFACE_VARIANT)
        val dark = listOf(LettaColorTokens.DARK_SURFACE, LettaColorTokens.DARK_BACKGROUND, LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH)
        for ((palette, surfaces) in listOf(LettaContextPalette.Light to light, LettaContextPalette.Dark to dark)) {
            for (colour in palette.all()) for (surface in surfaces) {
                val ratio = contrast(colour, Color(surface))
                assertTrue(ratio >= MIN_GRAPHIC_CONTRAST, "$colour on ${surface.toString(16)} is $ratio:1")
            }
        }
    }

    private val usage: ContextWindowUsage = run {
        val window = 200_000
        val sizes = listOf(
            ContextWindowSegmentKind.Messages to 52_000,
            ContextWindowSegmentKind.ToolDefinitions to 21_000,
            ContextWindowSegmentKind.CoreMemory to 14_000,
            ContextWindowSegmentKind.System to 9_000,
            ContextWindowSegmentKind.MemoryFiles to 7_000,
            ContextWindowSegmentKind.SummaryMemory to 5_000,
            ContextWindowSegmentKind.ToolRules to 4_000,
            ContextWindowSegmentKind.Directories to 3_000,
            ContextWindowSegmentKind.ExternalMemorySummary to 2_000,
            ContextWindowSegmentKind.Unitemised to 1_000,
        )
        val used = sizes.sumOf { it.second }
        ContextWindowUsage(
            usedTokens = used,
            maxTokens = window,
            freeTokens = window - used,
            segments = sizes.map { (kind, tokens) -> ContextWindowSegment(kind, kind.name, tokens, tokens.toFloat() / window) },
            freeSegment = ContextWindowSegment(ContextWindowSegmentKind.FreeSpace, "Free", window - used, (window - used).toFloat() / window),
        )
    }

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Column(
                    Modifier.width(width).background(MaterialTheme.colorScheme.surface).padding(16.dp).testTag(FRAME),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ContextUsageBar(usage)
                    ContextUsageRows(usage)
                }
            }
        }
        waitForIdle()
        val out = File("build/context-palette-snapshots").apply { mkdirs() }
            .resolve("context-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun breakdownAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    private companion object {
        const val MIN_GRAPHIC_CONTRAST = 3f
        const val FRAME = "context-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 700
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
