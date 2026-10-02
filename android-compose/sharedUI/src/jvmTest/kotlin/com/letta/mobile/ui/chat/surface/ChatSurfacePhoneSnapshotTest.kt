@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.PhoneScene
import com.letta.mobile.ui.devfixtures.PhoneSceneSurface
import com.letta.mobile.ui.devfixtures.PhoneScenes
import com.letta.mobile.ui.devfixtures.StandInMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.9: the shared page at phone size (412 x 915 dp, drawn at 2x) in the Touch
 * idiom: the full page with the legacy composer bar, and the canvas mode's bottom bar with the
 * chat head and its reply popup. On the canvas the top of the board is clear: the board's undo,
 * redo and more menu end its tool bar, and the head has no glow. Writes PNGs to
 * build/chat-surface-snapshots/phone-*.png for a reviewer; it asserts only that each rendered.
 *
 * The screens are [PhoneScenes] (sharedUI-devfixtures), the same ones desktop's phone playground
 * shows live.
 */
class ChatSurfacePhoneSnapshotTest {
    private class Shot(
        val scene: PhoneScene,
        /** Starts in the other mode and stops this far (ms) into the morph to the scene's presentation. */
        val morphMillis: Long? = null,
        /** False draws as Android does: no transport layer, each seat draws its character itself. */
        val transportLayer: Boolean = true,
    )

    private fun snapshot(scene: PhoneScene) {
        capture(Shot(scene))
    }

    private fun snapshot(shot: Shot) {
        capture(shot)
    }

    /** Renders and writes [shot], and hands back what it drew. */
    private fun capture(shot: Shot): BufferedImage {
        lateinit var image: BufferedImage
        render(shot) { image = it }
        return image
    }

    private fun render(shot: Shot, onImage: (BufferedImage) -> Unit) = runDesktopComposeUiTest(
        width = PhoneFixtures.PHONE_WIDTH_DP * SCALE,
        height = PhoneFixtures.PHONE_HEIGHT_DP * SCALE,
    ) {
        // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
        mainClock.autoAdvance = false
        val scene = shot.scene
        val start = if (shot.morphMillis != null) scene.presentation.other() else scene.presentation
        var presentation by mutableStateOf(start)
        val port = FixtureChatSessionPort(scene.state, scene.composer)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(SCALE.toFloat(), 1f)) {
                MaterialTheme(colorScheme = if (scene.dark) darkColorScheme() else lightColorScheme()) {
                    WithMascot(shot.transportLayer) {
                        PhoneSceneSurface(
                            scene = scene,
                            port = port,
                            presentation = presentation,
                            onIntent = {},
                        )
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        if (shot.morphMillis != null) {
            presentation = scene.presentation
            mainClock.advanceTimeBy(shot.morphMillis)
        }
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("${scene.id}.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
        onImage(image)
    }

    private fun ChatSurfacePresentation.other(): ChatSurfacePresentation =
        if (mode == ChatSurfaceMode.FullScreen) ChatSurfacePresentation.CanvasFirst else ChatSurfacePresentation.ChatFirst

    @Composable
    private fun WithMascot(transportLayer: Boolean, content: @Composable () -> Unit) {
        val shell = FakeMascotShell(PhoneFixtures.AGENT_ID, layerMounted = transportLayer)
        shell.Provide {
            CompositionLocalProvider(LocalMascotHost provides StandInMascotHost) {
                if (transportLayer) MascotTransportLayer { content() } else content()
            }
        }
    }

    @Test
    fun fullScreenDark() = snapshot(PhoneScenes.fullScreenDark)

    @Test
    fun fullScreenLight() = snapshot(PhoneScenes.fullScreenLight)

    @Test
    fun fullScreenTyping() = snapshot(PhoneScenes.fullScreenTyping)

    @Test
    fun fullScreenStreaming() = snapshot(PhoneScenes.fullScreenStreaming)

    @Test
    fun canvasIdle() = snapshot(PhoneScenes.canvasIdle)

    @Test
    fun canvasThinking() = snapshot(PhoneScenes.canvasThinking)

    @Test
    fun canvasReplyPopup() = snapshot(PhoneScenes.canvasReplyPopup)

    @Test
    fun canvasReplyPopupWithoutALayer() = snapshot(
        Shot(PhoneScenes.canvasReplyPopup.copy(id = "phone-canvas-reply-popup-no-layer"), transportLayer = false),
    )

    @Test
    fun canvasReplyPopupLight() = snapshot(PhoneScenes.canvasReplyPopupLight)

    @Test
    fun canvasHeadLeft() = snapshot(PhoneScenes.canvasHeadLeft)

    @Test
    fun canvasHeadRightTop() = snapshot(PhoneScenes.canvasHeadRightTop)

    /**
     * The canvas's bar and the chat page's bar with the same draft, their feet side by side
     * (phone-bar-side-by-side.png): one bar, the same rounded top, the same height.
     */
    @Test
    fun theCanvasBarMirrorsTheChatBar() {
        val canvasScene = PhoneScenes.canvasReplyPopupLight.copy(id = "phone-bar-canvas-light", composer = PhoneFixtures.typingComposer)
        val chatScene = canvasScene.copy(id = "phone-bar-chat-light", presentation = ChatSurfacePresentation.ChatFirst)
        val canvas = capture(Shot(canvasScene))
        val chat = capture(Shot(chatScene))
        val foot = FOOT_DP * SCALE
        val pair = BufferedImage(canvas.width * 2, foot, BufferedImage.TYPE_INT_ARGB)
        pair.createGraphics().apply {
            drawImage(canvas.getSubimage(0, canvas.height - foot, canvas.width, foot), 0, 0, null)
            drawImage(chat.getSubimage(0, chat.height - foot, chat.width, foot), canvas.width, 0, null)
            dispose()
        }
        val out = File("build/chat-surface-snapshots").resolve("phone-bar-side-by-side.png")
        ImageIO.write(pair, "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun canvasToChatMidMorph() = snapshot(
        Shot(PhoneScenes.canvasReplyPopup.copy(id = "phone-morph", presentation = ChatSurfacePresentation.ChatFirst), morphMillis = MID_MORPH_MILLIS),
    )

    @Test
    fun canvasToChatEarlyMorph() = snapshot(
        Shot(
            PhoneScenes.canvasReplyPopup.copy(id = "phone-morph-early", presentation = ChatSurfacePresentation.ChatFirst),
            morphMillis = EARLY_MORPH_MILLIS,
        ),
    )

    private companion object {
        const val EARLY_MORPH_MILLIS = 64L
        const val SCALE = 2
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
        const val MID_MORPH_MILLIS = 160L

        /** How much of the screen's foot the side-by-side bar comparison keeps, in dp. */
        const val FOOT_DP = 240
    }
}
