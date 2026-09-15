@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlin.test.Test

class CanvasWorkspaceUiTest {

    @Test
    fun canvasWorkspace_rendersAndRespondsToButtonClicks() = runComposeUiTest {
        setContent {
            CanvasWorkspace(
                initialJson = CanvasSamples.buildCycleJson,
            )
        }

        // Verify initial action buttons exist
        onNodeWithText("Import Build Cycle").assertExists()
        onNodeWithText("Import Daily Loop").assertExists()
        onNodeWithText("Clear").assertExists()
        onNodeWithText("Export JSON").assertExists()
        onNodeWithText("Export SVG").assertExists()

        // Click Clear button and assert state updates
        onNodeWithText("Clear").performClick()
        onNodeWithText("Elements: 0 | Cleared canvas").assertExists()

        // Click Import Build Cycle button and assert elements load
        onNodeWithText("Import Build Cycle").performClick()
        onNodeWithText("Elements: 15 | Imported Build Cycle sample").assertExists()

        // Click Import Daily Loop button and assert elements load
        onNodeWithText("Import Daily Loop").performClick()
        onNodeWithText("Imported Daily Loop sample", substring = true).assertExists()
    }
}