package com.letta.mobile.ui.devfixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import kotlinx.collections.immutable.persistentListOf

/**
 * One phone screen of the shared chat page: what the snapshot tests write as `[id].png` and the
 * phone playground shows live. Everything a screen needs except the window (density, theme, the
 * mascot renderer), which the caller provides.
 */
data class PhoneScene(
    /** The snapshot's file name, and the playground's key. */
    val id: String,
    /** What the playground's list calls it. */
    val label: String,
    val presentation: ChatSurfacePresentation,
    val dark: Boolean = true,
    /** The canvas mode needs a board under the page. */
    val withCanvas: Boolean = false,
    val dock: ChatDockGeometry = ChatDockGeometry.Default,
    val state: ChatUiState = PhoneFixtures.state,
    val composer: ChatComposerUiState = PhoneFixtures.idleComposer,
    /** The playground raises its stand-in keyboard for this screen (the snapshots have no keyboard). */
    val keyboardUp: Boolean = false,
    val appearance: ChatSurfaceAppearance = PhoneFixtures.touchAppearance,
)

/** The phone screens the snapshot tests and the playground share. */
object PhoneScenes {
    val fullScreenDark = PhoneScene("phone-full-screen-dark", "Chat - idle", ChatSurfacePresentation.ChatFirst)

    val fullScreenLight = fullScreenDark.copy(id = "phone-full-screen-light", label = "Chat - idle (light)", dark = false)

    val fullScreenTyping = fullScreenDark.copy(
        id = "phone-full-screen-typing",
        label = "Chat - typing",
        composer = PhoneFixtures.typingComposer,
    )

    val fullScreenStreaming = fullScreenDark.copy(
        id = "phone-full-screen-streaming",
        label = "Chat - streaming (Stop)",
        state = PhoneFixtures.streamingState,
    )

    val fullScreenImage = fullScreenDark.copy(
        id = "phone-full-screen-image",
        label = "Chat - image in a prompt",
        state = PhoneFixtures.state.copy(messages = persistentListOf(PhoneFixtures.messages[0], PhoneFixtures.messages[1])),
    )

    val fullScreenKeyboard = fullScreenTyping.copy(id = "phone-full-screen-keyboard", label = "Chat - keyboard up", keyboardUp = true)

    val canvasIdle = PhoneScene(
        "phone-canvas-idle",
        "Canvas - idle",
        ChatSurfacePresentation.CanvasFirst,
        withCanvas = true,
        state = PhoneFixtures.emptyState,
    )

    val canvasThinking = canvasIdle.copy(id = "phone-canvas-thinking", label = "Canvas - thinking", state = PhoneFixtures.thinkingState)

    val canvasReplyPopup = canvasIdle.copy(id = "phone-canvas-reply-popup", label = "Canvas - reply popup", state = PhoneFixtures.state)

    val canvasReplyPopupLight = canvasReplyPopup.copy(id = "phone-canvas-reply-popup-light", label = "Canvas - reply popup (light)", dark = false)

    val canvasHeadLeft = canvasReplyPopup.copy(
        id = "phone-canvas-head-left",
        label = "Canvas - head left",
        dock = ChatDockGeometry(anchorX = 0f, anchorY = HEAD_LEFT_Y),
    )

    val canvasHeadRightTop = canvasReplyPopup.copy(
        id = "phone-canvas-head-right-top",
        label = "Canvas - head right, top",
        dock = ChatDockGeometry(anchorX = 1f, anchorY = 0f),
        state = PhoneFixtures.shortReplyState,
    )

    val canvasKeyboard = canvasIdle.copy(
        id = "phone-canvas-keyboard",
        label = "Canvas - keyboard up",
        composer = PhoneFixtures.typingComposer,
        keyboardUp = true,
    )

    val receiptDark = fullScreenDark.copy(
        id = "canvas-artifact-phone-dark",
        label = "canvas.compose receipts",
        state = PhoneFixtures.canvasArtifactState,
        composer = ChatComposerUiState(canSend = true),
    )

    val receiptLight = receiptDark.copy(id = "canvas-artifact-phone-light", label = "canvas.compose receipts (light)", dark = false)

    /** In the playground's order. */
    val all: List<PhoneScene> = listOf(
        fullScreenDark,
        fullScreenLight,
        fullScreenTyping,
        fullScreenStreaming,
        fullScreenImage,
        fullScreenKeyboard,
        canvasIdle,
        canvasThinking,
        canvasReplyPopup,
        canvasReplyPopupLight,
        canvasHeadLeft,
        canvasHeadRightTop,
        canvasKeyboard,
        receiptDark,
        receiptLight,
    )

    private const val HEAD_LEFT_Y = 0.25f
}

/**
 * [scene] drawn as the phone draws it: the shared [ChatSurface] in the Touch idiom over the theme
 * background, with a compact [CanvasWorkspace] under it when the scene has a board. The caller owns
 * [presentation] (a snapshot holds it still; the playground reduces [onIntent] into it).
 */
@Composable
fun PhoneSceneSurface(
    scene: PhoneScene,
    port: ChatSessionPort,
    presentation: ChatSurfacePresentation,
    onIntent: (ChatSurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    host: ChatSurfaceHost = ChatSurfaceHost(openCanvas = {}, openAgentPane = {}, openAgentSwitcher = {}, showOnCanvas = {}),
    onDockGeometryChange: ((ChatDockGeometry) -> Unit)? = null,
    dock: ChatDockGeometry = scene.dock,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatSurface(
            port = port,
            presentation = presentation,
            onIntent = onIntent,
            host = host,
            appearance = scene.appearance,
            platform = ChatSurfacePlatform(showKeyboardHints = false, voiceInput = { _ -> MicStandIn() }),
            canvas = if (scene.withCanvas) {
                { _ -> CanvasWorkspace(showTitle = false, layout = CanvasLayout.COMPACT) }
            } else {
                null
            },
            dockGeometry = dock,
            onDockGeometryChange = onDockGeometryChange ?: {},
        )
    }
}

/** The platform's hold-to-dictate mic, as Android draws it: a primary-container disc. */
@Composable
fun MicStandIn() {
    Box(Modifier.fillMaxSize(MIC_FILL).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
}

private const val MIC_FILL = 0.9f
