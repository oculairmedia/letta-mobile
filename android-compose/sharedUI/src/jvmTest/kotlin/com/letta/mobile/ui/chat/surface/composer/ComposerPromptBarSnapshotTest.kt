@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.modelcontrol.testModelPicker
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/**
 * Draws the full page's prompt bar (pointer card and Touch bar) at the phone, tablet and desktop
 * widths and writes PNGs to build/composer-prompt-bar-snapshots, so a reviewer can see the bar
 * without a backend. It asserts only that it rendered.
 */
class ComposerPromptBarSnapshotTest {
    private val composer = ChatComposerUiState(
        text = "",
        model = ChatModelUiState(
            currentHandle = "anthropic/opus",
            currentLabel = "Opus 5.5",
            currentEffort = "high",
            options = persistentListOf(
                ChatModelOption(
                    handle = "anthropic/opus",
                    label = "Opus 5.5",
                    provider = "Anthropic",
                    reasoningEfforts = persistentListOf("low", "medium", "high"),
                ),
            ),
        ),
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Standard),
        contextUsage = ContextWindowUsageState(usage = ContextWindowUsage.total(usedTokens = 36_000, windowTokens = 200_000)),
    )

    private fun snapshot(input: LettaInput, width: Dp) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        val host = if (input == LettaInput.Pointer) LettaHost.Pointer else LettaHost(input = input)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ProvideLettaHost(host) {
                    Box(
                        Modifier
                            .width(width)
                            .wrapContentHeight()
                            .background(MaterialTheme.colorScheme.background)
                            .testTag(FRAME),
                    ) {
                        ChatComposerPanel(
                            ComposerInputs(
                                composer = composer,
                                actions = RecordingChatActions(),
                                host = ChatSurfaceHost(modelPicker = testModelPicker()),
                                platform = ChatSurfacePlatform(showKeyboardHints = input == LettaInput.Pointer),
                            ),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val image = onNodeWithTag(FRAME).captureToImage().toAwtImage()
        val name = "${input.name.lowercase()}-${width.value.toInt()}dp.png"
        val out = File("build/composer-prompt-bar-snapshots").apply { mkdirs() }.resolve(name)
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun pointerBarAtEveryWidth() = WIDTHS.forEach { snapshot(LettaInput.Pointer, it) }

    @Test
    fun touchBarAtEveryWidth() = WIDTHS.forEach { snapshot(LettaInput.Touch, it) }

    private companion object {
        const val FRAME = "prompt-bar-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 600
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
