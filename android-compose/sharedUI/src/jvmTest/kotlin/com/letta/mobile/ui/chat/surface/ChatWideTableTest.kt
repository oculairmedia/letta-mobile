@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.StandInMascotHost
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.markdown.SHARED_MARKDOWN_TABLE_TAG
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reply's six-column table ([PhoneFixtures.wideTableState]) on the full chat page. On a wide window
 * it breaks out of the reading column into the spare width beside it, centred on the column and no
 * wider than the wide-content cap; at 700dp, where the column fills the timeline, it stays in the
 * column as before. Each case also writes build/chat-surface-snapshots/wide-table-[width]-[theme].png
 * for a reviewer.
 */
class ChatWideTableTest {
    private fun ComposeUiTest.tableWidth(): Dp {
        val tables = onAllNodesWithTag(SHARED_MARKDOWN_TABLE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, tables.size, "the reply draws one table")
        return onAllNodesWithTag(SHARED_MARKDOWN_TABLE_TAG, useUnmergedTree = true)[0].getBoundsInRoot().let { it.right - it.left }
    }

    private fun render(widthDp: Int, dark: Boolean, check: ComposeUiTest.() -> Unit) {
        runDesktopComposeUiTest(width = widthDp, height = HEIGHT) {
            // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        WithMascot {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                ProvideLettaHost(if (widthDp < POINTER_MIN_WIDTH) LettaHost.Touch else LettaHost.Pointer) {
                                    ChatSurface(
                                        port = FixtureChatSessionPort(PhoneFixtures.wideTableState, ChatComposerUiState(canSend = true)),
                                        presentation = ChatSurfacePresentation.ChatFirst,
                                        onIntent = {},
                                        host = ChatSurfaceHost(openCanvas = {}, showOnCanvas = {}),
                                        platform = ChatSurfacePlatform(showKeyboardHints = false),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
            check()
            val image = onRoot().captureToImage().toAwtImage()
            val theme = if (dark) "dark" else "light"
            val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("wide-table-$widthDp-$theme.png")
            ImageIO.write(image, "png", out)
            assertTrue(out.length() > 0)
        }
    }

    @Composable
    private fun WithMascot(content: @Composable () -> Unit) {
        val shell = FakeMascotShell(PhoneFixtures.AGENT_ID)
        shell.Provide {
            CompositionLocalProvider(LocalMascotHost provides StandInMascotHost) {
                MascotTransportLayer { content() }
            }
        }
    }

    @Test
    fun wideWindowBreaksTheTableOutOfTheColumn() = render(VERY_WIDE, dark = true) {
        val width = tableWidth()
        assertTrue(width > ChatColumnMaxWidth, "the table spans $width, no wider than the ${ChatColumnMaxWidth} column")
        assertTrue(width <= LettaSurfaceSizes.wideContentMaxWidth, "the table spans $width, past the wide-content cap")
    }

    @Test
    fun narrowWindowKeepsTheTableInTheColumn() = render(NARROW, dark = true) {
        val width = tableWidth()
        assertTrue(width <= NARROW.dp, "the table spans $width of a ${NARROW}dp window")
        assertTrue(width <= ChatColumnMaxWidth, "the table spans $width, past the column")
    }

    @Test
    fun snapshots() {
        listOf(PHONE, NARROW, COLUMN, DESKTOP).forEach { width ->
            render(width, dark = true) {}
            render(width, dark = false) {}
        }
        render(VERY_WIDE, dark = false) {}
    }

    private companion object {
        const val PHONE = 360
        const val NARROW = 700
        const val COLUMN = 760
        const val DESKTOP = 1200
        const val VERY_WIDE = 1900
        const val HEIGHT = 900
        const val POINTER_MIN_WIDTH = 600
        const val SETTLE_FRAMES = 60
        const val FRAME_MILLIS = 16L
    }
}
