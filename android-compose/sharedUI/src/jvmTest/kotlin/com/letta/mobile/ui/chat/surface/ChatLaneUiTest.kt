@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.view.RuntimeViewId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rendered lane parity against the pre-lane column, not against a production ordering helper. */
class ChatLaneUiTest {
    @Test
    fun defaultFullPageMatchesLegacyAtAllWidths() {
        listOf(360, 700, 760, 1200).forEach { width -> parity(width, docked = false) }
    }

    @Test
    fun defaultDockMatchesLegacyAtAllWidths() {
        listOf(360, 700, 760, 1200).forEach { width -> parity(width, docked = true) }
    }

    private fun parity(width: Int, docked: Boolean) = runDesktopComposeUiTest(width = width, height = 800) {
        setPointerContent {
            Column {
                Box(Modifier.size(width.dp, 320.dp).testTag("legacy")) { Legacy(docked) }
                Box(Modifier.size(width.dp, 320.dp).testTag("lane")) {
                    ChatLane(
                        modifier = Modifier.fillMaxSize(),
                        docked = docked,
                        transcript = { Block(it, Color.Blue) },
                        status = { Block(Modifier.fillMaxWidth().height(12.dp), Color.Red) },
                        composer = { modifier, attached -> ComposerBlocks(modifier, attached) },
                    )
                }
            }
        }
        waitForIdle()
        val expected = onNodeWithTag("legacy").captureToImage().toPixelMap()
        val actual = onNodeWithTag("lane").captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(expected[x, y].toArgb(), actual[x, y].toArgb(), "pixel $x,$y at $width dp docked=$docked")
            }
        }
    }

    @Test
    fun reorderingAndHidingAStatusChangesTheRenderedOrder() = runDesktopComposeUiTest {
        setPointerContent {
            CompositionLocalProvider(
                LocalChatLane provides listOf(RuntimeViewId("chat.composer") to null, RuntimeViewId("chat.transcript") to null),
            ) {
                Box(Modifier.size(360.dp, 320.dp).testTag("reordered")) {
                    ChatLane(
                        modifier = Modifier.fillMaxSize(),
                        transcript = { Block(it, Color.Blue) },
                        status = { error("hidden status rendered") },
                        composer = { modifier, attached -> ComposerBlocks(modifier, attached) },
                    )
                }
            }
        }
        val pixels = onNodeWithTag("reordered").captureToImage().toPixelMap()
        assertEquals(Color.Green.toArgb(), pixels[0, 0].toArgb())
        assertEquals(Color.Blue.toArgb(), pixels[0, pixels.height - 1].toArgb())
    }
}

@Composable
private fun Legacy(docked: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Block(Modifier.weight(1f).fillMaxWidth(), Color.Blue)
        if (!docked) ComposerBlocks(Modifier.fillMaxWidth(), attached = true)
    }
}

@Composable
private fun ComposerBlocks(modifier: Modifier, attached: Boolean) {
    Column(modifier) {
        if (attached) Block(Modifier.fillMaxWidth().height(12.dp), Color.Red)
        Block(Modifier.fillMaxWidth().height(48.dp), Color.Green)
    }
}

@Composable
private fun Block(modifier: Modifier, color: Color) {
    Box(modifier.background(color))
}
