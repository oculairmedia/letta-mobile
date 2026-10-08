@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared fade (rail, desktop chat list, sideways strips) clears the faded edges and keeps the middle. */
class LettaFadingEdgesTest {

    private fun Color.lit(): Float = red * alpha

    @Test
    fun verticalFadeClearsTheTopAndBottomOnly() = runComposeUiTest {
        setContent {
            Box(Modifier.background(Color.Black)) {
                Box(
                    Modifier.size(100.dp).testTag("faded")
                        .lettaFadingEdges(topFadeAlpha = 1f, bottomFadeAlpha = 1f, topFadeLength = 20.dp, bottomFadeLength = 20.dp)
                        .background(Color.White),
                )
            }
        }
        val pixels = onNodeWithTag("faded").captureToImage().toPixelMap()
        assertEquals(1f, pixels[pixels.width / 2, pixels.height / 2].lit(), 0.01f)
        assertTrue(pixels[pixels.width / 2, 0].lit() < 0.1f)
        assertTrue(pixels[pixels.width / 2, pixels.height - 1].lit() < 0.1f)
        assertEquals(1f, pixels[0, pixels.height / 2].lit(), 0.01f)
    }

    @Test
    fun horizontalFadeClearsTheSidesOnly() = runComposeUiTest {
        setContent {
            Box(Modifier.background(Color.Black)) {
                Box(
                    Modifier.size(100.dp).testTag("faded")
                        .lettaHorizontalFadingEdges(startFadeAlpha = 1f, endFadeAlpha = 1f, fadeLength = 20.dp)
                        .background(Color.White),
                )
            }
        }
        val pixels = onNodeWithTag("faded").captureToImage().toPixelMap()
        assertEquals(1f, pixels[pixels.width / 2, pixels.height / 2].lit(), 0.01f)
        assertTrue(pixels[0, pixels.height / 2].lit() < 0.1f)
        assertTrue(pixels[pixels.width - 1, pixels.height / 2].lit() < 0.1f)
        assertEquals(1f, pixels[pixels.width / 2, 0].lit(), 0.01f)
    }
}
