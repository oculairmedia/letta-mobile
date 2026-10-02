package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.compose.CanvasComposeColors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * canvas_compose writes its colour presets as tints of the workspace's own note palette
 * (letta-mobile-bglj6.10): sharedLogic cannot see [NoteColors], so this holds the two together.
 */
class CanvasComposeColorsTest {
    @Test
    fun everyComposePresetIsANoteColour() {
        val palette = NoteColors.map { it.hex.lowercase() }.toSet()
        CanvasComposeColors.PRESETS.forEach { (name, hex) ->
            assertTrue(hex in palette, "compose preset $name ($hex) is not one of the note colours $palette")
        }
    }
}
