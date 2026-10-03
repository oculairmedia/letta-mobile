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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.StandInMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.13: the canvas_compose cards on the full-screen page, at a desktop size in the
 * Pointer idiom and at phone size (412 x 915 dp, drawn at 2x) in the Touch idiom, light and dark:
 * a published artifact on its narration, one still being added, and a refused one. Writes PNGs to
 * build/chat-surface-snapshots/canvas-artifact-*.png for a reviewer; it asserts only that each
 * rendered. The conversation is [PhoneFixtures.canvasArtifactState] (sharedUI-devfixtures), which the
 * desktop phone playground shows live.
 */
class ChatCanvasArtifactSnapshotTest {
    private class Shot(val name: String, val phone: Boolean, val dark: Boolean)

    private fun snapshot(shot: Shot) {
        val width = if (shot.phone) PhoneFixtures.PHONE_WIDTH_DP * SCALE else DESKTOP_WIDTH
        val height = if (shot.phone) PhoneFixtures.PHONE_HEIGHT_DP * SCALE else DESKTOP_HEIGHT
        runDesktopComposeUiTest(width = width, height = height) {
            // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
            mainClock.autoAdvance = false
            setContent {
                val density = if (shot.phone) Density(SCALE.toFloat(), 1f) else LocalDensity.current
                CompositionLocalProvider(LocalDensity provides density) {
                    MaterialTheme(colorScheme = if (shot.dark) darkColorScheme() else lightColorScheme()) {
                        WithMascot {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                ChatSurface(
                                    port = FixtureChatSessionPort(PhoneFixtures.canvasArtifactState, ChatComposerUiState(canSend = true)),
                                    presentation = ChatSurfacePresentation.ChatFirst,
                                    onIntent = {},
                                    host = ChatSurfaceHost(openCanvas = {}, showOnCanvas = {}),
                                    appearance = if (shot.phone) {
                                        ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch, toolDetails = ChatToolDetails.Sheet)
                                    } else {
                                        ChatSurfaceAppearance()
                                    },
                                    platform = ChatSurfacePlatform(showKeyboardHints = !shot.phone),
                                )
                            }
                        }
                    }
                }
            }
            repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
            val image = onRoot().captureToImage().toAwtImage()
            val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("${shot.name}.png")
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
    fun desktopLight() = snapshot(Shot("canvas-artifact-desktop-light", phone = false, dark = false))

    @Test
    fun desktopDark() = snapshot(Shot("canvas-artifact-desktop-dark", phone = false, dark = true))

    @Test
    fun phoneLight() = snapshot(Shot("canvas-artifact-phone-light", phone = true, dark = false))

    @Test
    fun phoneDark() = snapshot(Shot("canvas-artifact-phone-dark", phone = true, dark = true))

    private companion object {
        const val DESKTOP_WIDTH = 1280
        const val DESKTOP_HEIGHT = 900
        const val SCALE = 2
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
    }
}
