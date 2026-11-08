@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.setPointerContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ComposerSendCornerUiTest {
    private fun ComposeUiTest.show(mode: ChatSurfaceMode, streaming: Boolean = false, tall: Boolean = false) {
        setPointerContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMinimisedBarLayout provides if (tall) MinimisedBarLayout(120.dp, null) else null) {
                    Box(Modifier.width(600.dp)) {
                        ChatComposerPanel(
                            ComposerInputs(
                                composer = ChatComposerUiState(text = if (streaming) "" else "A long draft\n".repeat(12), canSend = true),
                                actions = RecordingChatActions(), mode = mode, uiState = ChatUiState(isStreaming = streaming),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.assertCorner(surface: String, action: String) {
        val bounds = onNodeWithTag(surface).getUnclippedBoundsInRoot()
        val button = onNodeWithTag(action).getUnclippedBoundsInRoot()
        val top = button.top - bounds.top
        val end = bounds.right - button.right
        assertTrue(abs((top - end).value) <= 1f / density.density, "top inset $top differs from end inset $end")
    }

    @Test fun fullCardCorner() = runComposeUiTest {
        show(ChatSurfaceMode.FullScreen)
        assertCorner(ComposerTestTags.CARD, ComposerTestTags.SEND)
    }

    @Test fun sendIsOutsideControls() = runComposeUiTest {
        show(ChatSurfaceMode.FullScreen)
        val sendInControls = hasTestTag(ComposerTestTags.SEND) and hasAnyAncestor(hasTestTag(ComposerTestTags.CONTROLS))
        assertEquals(0, onAllNodes(sendInControls, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun longDraftClearsSend() = runComposeUiTest {
        show(ChatSurfaceMode.FullScreen)
        val field = onNodeWithTag(ComposerTestTags.INPUT).getUnclippedBoundsInRoot()
        val send = onNodeWithTag(ComposerTestTags.SEND).getUnclippedBoundsInRoot()
        assertTrue(field.right <= send.left, "field $field overlaps send $send")
    }

    @Test fun dockedCorner() = runComposeUiTest {
        show(ChatSurfaceMode.Docked)
        assertCorner(ComposerTestTags.DOCKED_BAR, ComposerTestTags.SEND)
    }

    @Test fun tallDockedCorner() = runComposeUiTest {
        show(ChatSurfaceMode.Docked, tall = true)
        assertCorner(ComposerTestTags.DOCKED_BAR, ComposerTestTags.SEND)
        val field = onNodeWithTag(ComposerTestTags.DOCKED_INPUT).getUnclippedBoundsInRoot()
        val send = onNodeWithTag(ComposerTestTags.SEND).getUnclippedBoundsInRoot()
        assertTrue(field.right <= send.left, "field $field overlaps send $send")
    }

    @Test fun stopUsesTheSameCorner() = runComposeUiTest {
        show(ChatSurfaceMode.FullScreen, streaming = true)
        assertCorner(ComposerTestTags.CARD, ComposerTestTags.STOP)
    }

    @Test fun dockedStopUsesTheSameCorner() = runComposeUiTest {
        show(ChatSurfaceMode.Docked, streaming = true)
        assertCorner(ComposerTestTags.DOCKED_BAR, ComposerTestTags.STOP)
    }

    @Test fun captureCard() = runComposeUiTest {
        show(ChatSurfaceMode.FullScreen)
        capture(ComposerTestTags.CARD, "card")
    }

    @Test fun captureDocked() = runComposeUiTest {
        show(ChatSurfaceMode.Docked)
        capture(ComposerTestTags.DOCKED_BAR, "docked")
    }

    private fun ComposeUiTest.capture(tag: String, name: String) {
        val directory = File("build/reports/composer-send-corner").apply { mkdirs() }
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", directory.resolve("$name.png"))
    }
}
