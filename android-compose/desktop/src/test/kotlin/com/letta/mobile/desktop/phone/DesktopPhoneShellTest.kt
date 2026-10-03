@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.desktop.canvas.DesktopCanvasOwner
import com.letta.mobile.desktop.chat.DesktopChatComposerHostInputs
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.DesktopSharedChatPage
import com.letta.mobile.desktop.chat.DesktopSharedChatPageNavigation
import com.letta.mobile.desktop.chat.DesktopSharedChatPageState
import com.letta.mobile.desktop.chat.FakeDesktopChatGateway
import com.letta.mobile.desktop.chat.noOpDesktopTimelinePersistence
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.FixtureMascotShell
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.PhoneScene
import com.letta.mobile.ui.devfixtures.PhoneSceneSurface
import com.letta.mobile.ui.devfixtures.PhoneScenes
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

/**
 * The phone preview's shell (docs/development/phone-preview.md): its simulated insets reach the
 * code that reads `WindowInsets.*` and the inset-padding modifiers, its keyboard moves them, and the
 * desktop's shared chat page under it draws the Touch idiom.
 */
class DesktopPhoneShellTest {
    private fun phoneState() = DesktopPhoneState(initialDark = true).apply {
        deviceFrame = false
        showShortcuts = false
    }

    @Test
    fun theSimulatedSystemBarsAndKeyboardAreTheWindowInsets() = runDesktopComposeUiTest(width = PHONE_W, height = PHONE_H) {
        val state = phoneState()
        var density = Density(1f)
        var ime = -1
        var navigation = -1
        var status = -1
        var safeBottom = -1
        var paddedHeight = -1
        var screenHeight = -1
        setContent {
            DesktopPhoneScreen(state) {
                density = LocalDensity.current
                ime = WindowInsets.ime.getBottom(density)
                navigation = WindowInsets.navigationBars.getBottom(density)
                status = WindowInsets.statusBars.getTop(density)
                safeBottom = WindowInsets.safeDrawing.getBottom(density)
                Box(Modifier.fillMaxSize().onSizeChanged { screenHeight = it.height }) {
                    // What ChatComposerPanel and DockedChatPanel do with the keyboard.
                    Box(Modifier.fillMaxSize().imePadding().onSizeChanged { paddedHeight = it.height })
                }
            }
        }
        waitForIdle()
        val device = state.device
        assertEquals(0, ime, "no keyboard at rest")
        assertEquals(px(device.navigationBarDp, density), navigation)
        assertEquals(px(device.statusBarDp, density), status)
        assertEquals(navigation, safeBottom, "safeDrawing's foot is the gesture bar")
        assertEquals(screenHeight, paddedHeight)

        state.keyboardVisible = true
        waitForIdle()
        // As Android reports it: the keyboard plus the gesture bar beneath it.
        val keyboard = px(device.keyboardDp + device.navigationBarDp, density)
        assertEquals(keyboard, ime)
        assertEquals(keyboard, safeBottom)
        assertEquals(screenHeight - keyboard, paddedHeight, "imePadding() lifts content over the keyboard")
        onNodeWithTag(PhoneScreenTags.KEYBOARD).assertExists()

        state.keyboardVisible = false
        waitForIdle()
        assertEquals(0, ime)
        assertEquals(screenHeight, paddedHeight)
    }

    @Test
    fun aTextFieldTakingFocusRaisesTheKeyboard() = runDesktopComposeUiTest(width = PHONE_W, height = PHONE_H) {
        // The chat page's mascot and glow keep frames coming, so it never idles: step the clock.
        mainClock.autoAdvance = false
        val state = phoneState()
        val port = FixtureChatSessionPort(PhoneFixtures.state, PhoneFixtures.idleComposer)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DesktopPhoneScreen(state) {
                    FixtureMascotShell { PhoneSceneSurface(PhoneScenes.fullScreenDark, port, PhoneScenes.fullScreenDark.presentation, onIntent = {}) }
                }
            }
        }
        settle()
        assertTrue(!state.keyboardVisible)
        onNodeWithTag(ComposerTestTags.INPUT, useUnmergedTree = true).requestFocus()
        settle()
        assertTrue(state.keyboardVisible, "focusing the composer raises the keyboard, as on Android")
    }

    @Test
    fun theDesktopChatPageDrawsTheTouchIdiomUnderThePhoneShell() = runDesktopComposeUiTest(width = PHONE_W, height = PHONE_H) {
        // The chat page's mascot and glow keep frames coming, so it never idles: step the clock.
        mainClock.autoAdvance = false
        val state = phoneState()
        val chrome = DesktopPhoneChrome(state)
        val scope = TestScope()
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = scope,
            gatewayFactory = { FakeDesktopChatGateway() },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
        val port = DesktopChatSessionPort(controller = controller, scope = scope.backgroundScope)
        setContent {
            CompositionLocalProvider(LocalDesktopPhone provides chrome) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    DesktopPhoneScreen(state) {
                        DesktopSharedChatPage(
                            state = DesktopSharedChatPageState(
                                port = port,
                                pagedTimeline = null,
                                hostInputs = DesktopChatComposerHostInputs(),
                                errorMessage = null,
                                canvasStore = InMemoryCanvasDocumentStore(),
                                canvasOwner = DesktopCanvasOwner(conversationId = null, agentId = null, agentName = "Meridian"),
                                dockGeometry = mutableStateOf(ChatDockGeometry.Default),
                            ),
                            navigation = DesktopSharedChatPageNavigation(openCanvas = {}, openAgent = {}, openModelPicker = {}),
                        )
                    }
                }
            }
        }
        settle()
        // Canvas first, as on Android: the canvas's chat head and bar, not the desktop's floating panel.
        onNodeWithTag(TOUCH_DOCK_TAG).assertExists()
        onNodeWithTag(ComposerTestTags.TOUCH_BAR, useUnmergedTree = true).assertExists()
        onNodeWithTag(ComposerTestTags.CARD, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.HINT, useUnmergedTree = true).assertDoesNotExist()
        // The canvas mode keeps the top of the board clear: no header.
        onNodeWithTag(PhoneShellTags.CHAT_HEADER).assertDoesNotExist()

        // The Touch bar stands on the simulated keyboard (TouchComposerBar reads WindowInsets.ime).
        val barBottom = onNodeWithTag(ComposerTestTags.TOUCH_BAR, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        state.keyboardVisible = true
        settle()
        val raisedBottom = onNodeWithTag(ComposerTestTags.TOUCH_BAR, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(raisedBottom < barBottom - KEYBOARD_LIFT_FLOOR_PX, "the bar rose with the keyboard: $barBottom -> $raisedBottom")
        controller.close()
    }

    /**
     * The phone shell around fixture screens, written to build/phone-preview/ for a reviewer: the
     * canvas with the reply popup, and the chat with the keyboard up.
     */
    @Test
    fun rendersThePhoneShellAroundFixtureScreens() {
        render(PhoneScenes.canvasReplyPopup, "phone-shell-canvas-reply-popup")
        render(PhoneScenes.fullScreenKeyboard, "phone-shell-chat-keyboard")
    }

    private fun render(scene: PhoneScene, name: String) = runDesktopComposeUiTest(width = PHONE_W * 2 + FRAME_PX, height = PHONE_H * 2 + FRAME_PX) {
        mainClock.autoAdvance = false
        val state = DesktopPhoneState(initialDark = scene.dark, initialZoom = 2f).apply {
            showShortcuts = false
            keyboardVisible = scene.keyboardUp
        }
        var presentation by mutableStateOf(scene.presentation)
        val port = FixtureChatSessionPort(scene.state, scene.composer)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    DesktopPhoneScreen(state) {
                        FixtureMascotShell {
                            PhoneSceneSurface(scene, port, presentation, onIntent = { presentation = ChatSurfaceModeReducer.reduce(presentation, it) })
                        }
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        val image = onRoot().captureToImage().toAwtImage()
        val out = File(PHONE_PREVIEW_OUTPUT_DIR).apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.settle() {
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
    }

    private fun px(dp: Int, density: Density): Int = (dp * density.density).roundToInt()

    private companion object {
        const val PHONE_W = 412
        const val PHONE_H = 915
        const val FRAME_PX = 40
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
        const val KEYBOARD_LIFT_FLOOR_PX = 100f

        /** TouchCanvasDock's root tag (internal to sharedUI). */
        const val TOUCH_DOCK_TAG = "chat-touch-dock"
    }
}
