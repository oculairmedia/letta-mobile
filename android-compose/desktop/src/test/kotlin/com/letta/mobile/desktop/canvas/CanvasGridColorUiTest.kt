@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** The grid is drawn in the colour and spacing the background menu sets, not a fixed grey. */
class CanvasGridColorUiTest {

    @Test
    fun theGridTakesThePatternColourAndSpacing() = runComposeUiTest {
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = ""))
        }
        runBlocking { session.setBackgroundPattern(CanvasBackgroundPattern(kind = CanvasBackgroundPattern.GRID, spacing = 32f, colorHex = "#ff0000")) }
        setContent { Box(Modifier.size(400.dp, 300.dp)) { CanvasWorkspace(session = session) } }
        mainClock.advanceTimeBy(1000)
        waitForIdle()

        val image = onNodeWithContentDescription("Canvas board").captureToImage().toAwtImage()
        // At 100% with no pan the vertical lines fall on multiples of 32px, one crisp pixel wide:
        // sample a column of one, away from the chrome, and count solid red pixels.
        val redOnLines = (120 until 180).count { y ->
            val rgb = image.getRGB(160, y)
            val r = rgb shr 16 and 0xff
            val g = rgb shr 8 and 0xff
            val b = rgb and 0xff
            r > 150 && g < 100 && b < 100
        }
        assertTrue(redOnLines > 50, "the grid line at x=160 should be red, $redOnLines of 60 pixels were")
        val between = image.getRGB(176, 150)
        assertTrue((between shr 16 and 0xff) < 150 || (between shr 8 and 0xff) > 100, "between lines is the board, not grid")
    }
}
