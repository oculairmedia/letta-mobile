@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.components.field.LettaFieldState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the pointer prompt card at rest, under the pointer and focused, in both themes, at the phone,
 * tablet and desktop widths, and writes PNGs to build/composer-field-snapshots for review. It asserts
 * only that it rendered.
 */
class ComposerFieldBorderSnapshotTest {
    private val composer = ChatComposerUiState(
        text = "",
        model = ChatModelUiState(currentHandle = null, currentLabel = "Opus 5.5"),
    )

    private fun snapshot(look: LettaFieldState, width: Dp, dark: Boolean) =
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                ProvideLettaHost(LettaHost.Pointer) {
                    Box(
                        Modifier
                            .width(width)
                            .wrapContentHeight()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 8.dp)
                            .testTag(FRAME),
                    ) {
                        ChatComposerPanel(
                            ComposerInputs(
                                composer = composer,
                                actions = RecordingChatActions(),
                                platform = ChatSurfacePlatform(showKeyboardHints = true),
                            ),
                        )
                    }
                }
            }
        }
        when (look) {
            LettaFieldState.Rest -> Unit
            LettaFieldState.Hover -> onNodeWithTag(ComposerTestTags.CARD).performMouseInput { moveTo(center) }
            LettaFieldState.Focus -> onNodeWithTag(ComposerTestTags.INPUT).requestFocus()
        }
        waitForIdle()
        val image = onNodeWithTag(FRAME).captureToImage().toAwtImage()
        val theme = if (dark) "dark" else "light"
        val out = File("build/composer-field-snapshots").apply { mkdirs() }
            .resolve("${look.name.lowercase()}-$theme-${width.value.toInt()}dp.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun everyLookAtEveryWidth() = LettaFieldState.entries.forEach { look ->
        WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(look, width, it) } }
    }

    private companion object {
        const val FRAME = "composer-field-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 600
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
