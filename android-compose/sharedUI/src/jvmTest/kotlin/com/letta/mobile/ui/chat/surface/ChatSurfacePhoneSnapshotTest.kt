@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bglj6.1.9: the shared page at phone size (412 x 915 dp, drawn at 2x) in the Touch
 * idiom: the full page with the legacy composer bar, and the canvas mode's bottom bar with the
 * chat head and its reply popup. Writes PNGs to build/chat-surface-snapshots/phone-*.png for a
 * reviewer; it asserts only that each rendered.
 */
class ChatSurfacePhoneSnapshotTest {
    private class FixturePort(state: ChatUiState, composer: ChatComposerUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(composer)
        override val actions: ChatActions = RecordingChatActions()
    }

    private val screenshot = UiImageAttachment(base64 = sampleImageBase64(), mediaType = "image/png")

    private val messages = persistentListOf(
        UiMessage(
            id = "u0",
            role = "user",
            content = "just testing heres where we now are",
            timestamp = "2026-10-01T10:18:00Z",
            attachments = listOf(screenshot),
        ),
        UiMessage(
            id = "a0",
            role = "assistant",
            content = "I can see it. The architecture column is on the left and a blue cursive \"Hello World\" sits in the middle.",
            timestamp = "2026-10-01T10:18:09Z",
            runId = "run-0",
        ),
        UiMessage(id = "u1", role = "user", content = "Plan a shopping list for a taco night for six.", timestamp = "2026-10-01T10:19:00Z"),
        UiMessage(
            id = "a1",
            role = "assistant",
            content = LONG_REPLY,
            timestamp = "2026-10-01T10:19:09Z",
            runId = "run-1",
            toolCalls = listOf(
                UiToolCall(name = "canvas_add_element", arguments = "{\"kind\":\"checklist\"}", result = "ok", status = "success", toolCallId = "t1"),
            ),
        ),
    )

    private val state = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        messages = messages,
        isLoadingMessages = false,
        agentName = "Meridian",
        agentId = "agent-1",
    )

    private val idleComposer = ChatComposerUiState(
        canSend = true,
        model = ChatModelUiState(currentHandle = "lmstudio/MiniMax-M3", currentLabel = "lmstudio/MiniMax-M3"),
    )

    private val touch = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch, toolDetails = ChatToolDetails.Sheet)

    private class Shot(
        val name: String,
        val presentation: ChatSurfacePresentation,
        val dark: Boolean = true,
        val withCanvas: Boolean = false,
        val dock: ChatDockGeometry = ChatDockGeometry.Default,
        val uiState: ChatUiState? = null,
        val composer: ChatComposerUiState? = null,
        /** Starts in the other mode and stops this far (ms) into the morph to [presentation]. */
        val morphMillis: Long? = null,
        /** False draws as Android does: no transport layer, each seat draws its character itself. */
        val transportLayer: Boolean = true,
    )

    private fun snapshot(shot: Shot) {
        capture(shot)
    }

    /** Renders and writes [shot], and hands back what it drew. */
    private fun capture(shot: Shot): BufferedImage {
        lateinit var image: BufferedImage
        render(shot) { image = it }
        return image
    }

    private fun render(shot: Shot, onImage: (BufferedImage) -> Unit) = runDesktopComposeUiTest(width = PHONE_WIDTH_DP * SCALE, height = PHONE_HEIGHT_DP * SCALE) {
        // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
        mainClock.autoAdvance = false
        val start = if (shot.morphMillis != null) shot.presentation.other() else shot.presentation
        var presentation by mutableStateOf(start)
        val port = FixturePort(shot.uiState ?: state, shot.composer ?: idleComposer)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(SCALE.toFloat(), 1f)) {
                MaterialTheme(colorScheme = if (shot.dark) darkColorScheme() else lightColorScheme()) {
                    WithMascot(shot.transportLayer) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            ChatSurface(
                                port = port,
                                presentation = presentation,
                                onIntent = {},
                                host = ChatSurfaceHost(openCanvas = {}, openAgentPane = {}),
                                appearance = touch,
                                platform = ChatSurfacePlatform(showKeyboardHints = false, voiceInput = { _ -> MicStandIn() }),
                                canvas = if (shot.withCanvas) {
                                    { _ -> CanvasWorkspace(showTitle = false, layout = CanvasLayout.COMPACT) }
                                } else {
                                    null
                                },
                                dockGeometry = shot.dock,
                            )
                        }
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        if (shot.morphMillis != null) {
            presentation = shot.presentation
            mainClock.advanceTimeBy(shot.morphMillis)
        }
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("${shot.name}.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
        onImage(image)
    }

    private fun ChatSurfacePresentation.other(): ChatSurfacePresentation =
        if (mode == ChatSurfaceMode.FullScreen) ChatSurfacePresentation.CanvasFirst else ChatSurfacePresentation.ChatFirst

    /** The platform's hold-to-dictate mic, as Android draws it: a primary-container disc. */
    @Composable
    private fun MicStandIn() {
        Box(
            Modifier.fillMaxSize(MIC_FILL).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        )
    }

    @Composable
    private fun WithMascot(transportLayer: Boolean, content: @Composable () -> Unit) {
        val shell = FakeMascotShell("agent-1", layerMounted = transportLayer)
        shell.Provide {
            CompositionLocalProvider(LocalMascotHost provides StandInMascotHost) {
                if (transportLayer) MascotTransportLayer { content() } else content()
            }
        }
    }

    /** Paints a body and a face filling ~60 % of the seat, like the real characters. */
    private object StandInMascotHost : MascotHost {
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = FakeMascotHost.entry(agentId, identity)

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(BODY).background(BODY_COLOR, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(FACE_WIDTH).fillMaxHeight(FACE_HEIGHT).background(Color.White, CircleShape))
                }
            }
        }

        private const val BODY = 0.6f
        private const val FACE_WIDTH = 0.55f
        private const val FACE_HEIGHT = 0.3f
        private val BODY_COLOR = Color(0xFF00AA88)
    }

    @Test
    fun fullScreenDark() = snapshot(Shot("phone-full-screen-dark", ChatSurfacePresentation.ChatFirst))

    @Test
    fun fullScreenLight() = snapshot(Shot("phone-full-screen-light", ChatSurfacePresentation.ChatFirst, dark = false))

    @Test
    fun fullScreenTyping() = snapshot(
        Shot("phone-full-screen-typing", ChatSurfacePresentation.ChatFirst, composer = idleComposer.copy(text = "Make it vegetarian")),
    )

    @Test
    fun fullScreenStreaming() = snapshot(
        Shot("phone-full-screen-streaming", ChatSurfacePresentation.ChatFirst, uiState = state.copy(isStreaming = true, isAgentTyping = true)),
    )

    @Test
    fun canvasIdle() = snapshot(Shot("phone-canvas-idle", ChatSurfacePresentation.CanvasFirst, withCanvas = true, uiState = state.copy(messages = persistentListOf())))

    @Test
    fun canvasThinking() = snapshot(
        Shot(
            "phone-canvas-thinking",
            ChatSurfacePresentation.CanvasFirst,
            withCanvas = true,
            uiState = state.copy(messages = persistentListOf(messages[2]), isAgentTyping = true),
        ),
    )

    @Test
    fun canvasReplyPopup() = snapshot(Shot("phone-canvas-reply-popup", ChatSurfacePresentation.CanvasFirst, withCanvas = true))

    @Test
    fun canvasReplyPopupWithoutALayer() = snapshot(
        Shot("phone-canvas-reply-popup-no-layer", ChatSurfacePresentation.CanvasFirst, withCanvas = true, transportLayer = false),
    )

    @Test
    fun canvasReplyPopupLight() =
        snapshot(Shot("phone-canvas-reply-popup-light", ChatSurfacePresentation.CanvasFirst, dark = false, withCanvas = true))

    @Test
    fun canvasHeadLeft() = snapshot(
        Shot(
            "phone-canvas-head-left",
            ChatSurfacePresentation.CanvasFirst,
            withCanvas = true,
            dock = ChatDockGeometry(anchorX = 0f, anchorY = 0.25f),
        ),
    )

    @Test
    fun canvasHeadRightTop() = snapshot(
        Shot(
            "phone-canvas-head-right-top",
            ChatSurfacePresentation.CanvasFirst,
            withCanvas = true,
            dock = ChatDockGeometry(anchorX = 1f, anchorY = 0f),
            uiState = state.copy(messages = persistentListOf(messages[2], messages[3].copy(content = "Done: the list is on the board."))),
        ),
    )

    /**
     * The canvas's bar and the chat page's bar with the same draft, their feet side by side
     * (phone-bar-side-by-side.png): one bar, the same rounded top, the same height.
     */
    @Test
    fun theCanvasBarMirrorsTheChatBar() {
        val draft = idleComposer.copy(text = "Make it vegetarian")
        val canvas = capture(Shot("phone-bar-canvas-light", ChatSurfacePresentation.CanvasFirst, dark = false, withCanvas = true, composer = draft))
        val chat = capture(Shot("phone-bar-chat-light", ChatSurfacePresentation.ChatFirst, dark = false, withCanvas = true, composer = draft))
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
        Shot("phone-morph", ChatSurfacePresentation.ChatFirst, withCanvas = true, morphMillis = MID_MORPH_MILLIS),
    )

    @Test
    fun canvasToChatEarlyMorph() = snapshot(
        Shot("phone-morph-early", ChatSurfacePresentation.ChatFirst, withCanvas = true, morphMillis = EARLY_MORPH_MILLIS),
    )

    private companion object {
        const val EARLY_MORPH_MILLIS = 64L
        const val PHONE_WIDTH_DP = 412
        const val PHONE_HEIGHT_DP = 915
        const val SCALE = 2
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
        const val MID_MORPH_MILLIS = 160L
        const val MIC_FILL = 0.9f

        /** How much of the screen's foot the side-by-side bar comparison keeps, in dp. */
        const val FOOT_DP = 240

        const val LONG_REPLY = "Here's a list for **six people**:\n\n" +
            "- 2 lb ground beef, or black beans for the vegetarians\n" +
            "- 18 corn tortillas and a dozen flour ones\n" +
            "- Salsa, limes, cilantro and a white onion\n" +
            "- Two avocados for guacamole, plus a jalapeño\n" +
            "- Shredded cheese, sour cream, lettuce\n\n" +
            "I put it on the board as a checklist so you can tick things off at the shop."

        /** A small gradient PNG standing in for a screenshot the person attached. */
        fun sampleImageBase64(): String {
            val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.paint = GradientPaint(0f, 0f, java.awt.Color(0x1B2A3A), 320f, 200f, java.awt.Color(0x3C6E9F))
            g.fillRect(0, 0, 320, 200)
            g.color = java.awt.Color(0x8AB4F8)
            g.drawString("Hello World", 120, 105)
            g.dispose()
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
