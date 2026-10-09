package com.letta.mobile.desktop.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.AgentIdentity
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.DefaultFontScaleRange
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.StateFlow

/**
 * How the host frames the shared chat page: on the desktop, nothing; on the phone preview, the
 * status bar and the floating header the page draws under ([topChromeInset], Android's
 * `topChromeInset`), and no keyboard-shortcut strip.
 */
@Immutable
internal data class ChatPageFrame(
    val topChromeInset: Dp = 0.dp,
    val showKeyboardHints: Boolean = true,
)

/** What the phone's header needs from the page: its mode, the conversation, and the way back to the canvas. */
internal class PhoneChatPageInputs(
    val mode: ChatSurfaceMode,
    val uiState: StateFlow<ChatUiState>,
    val onBackToCanvas: () -> Unit,
)

/** The phone opens conversations on the canvas, as Android does; the desktop follows its preference. */
internal fun DesktopPhoneChrome?.opensOnCanvas(preference: Boolean): Boolean = this != null || preference

/** The phone's idiom, as Android's SharedChatPage sets it; [desktop] when there is no phone. */
internal fun DesktopPhoneChrome?.chatAppearance(desktop: ChatSurfaceAppearance): ChatSurfaceAppearance =
    if (this == null) desktop else PhoneChatAppearance

private val PhoneChatAppearance = ChatSurfaceAppearance(
    platformStyle = ChatPlatformStyle.Touch,
    toolDetails = ChatToolDetails.Sheet,
    fontScaleRange = DefaultFontScaleRange,
)

/**
 * Hosts the shared chat [page] as this window draws it. Without a phone it is exactly
 * `page(modifier, ChatPageFrame())`. On the phone the page fills the screen edge to edge. Over the
 * full-screen chat the floating header ([PhoneChatHeader]) shows; in canvas mode only the shared
 * agent pill does ([PhoneCanvasIdentityPill], Android's canvas identity pill), narrow enough to
 * leave the board's own actions clear.
 */
@Composable
internal fun DesktopPhoneChatFrame(
    phone: DesktopPhoneChrome?,
    inputs: PhoneChatPageInputs,
    modifier: Modifier = Modifier,
    page: @Composable (Modifier, ChatPageFrame) -> Unit,
) {
    if (phone == null) {
        page(modifier, ChatPageFrame())
        return
    }
    var headerHeight by remember { mutableStateOf(0.dp) }
    val fullScreen = inputs.mode == ChatSurfaceMode.FullScreen
    val frame = ChatPageFrame(topChromeInset = phoneTopChromeInset(fullScreen, headerHeight), showKeyboardHints = false)
    Box(modifier.fillMaxSize()) {
        page(Modifier, frame)
        PhoneChatPageHeader(phone, inputs, fullScreen) { headerHeight = it }
    }
}

/** Its own composable, so the agent's name is the only thing it collects and the page does not recompose with it. */
@Composable
private fun PhoneChatPageHeader(
    phone: DesktopPhoneChrome,
    inputs: PhoneChatPageInputs,
    fullScreen: Boolean,
    onHeightChange: (Dp) -> Unit,
) {
    val state by inputs.uiState.collectAsState()
    val identity = remember(state.agentId, state.agentName, phone) {
        phoneAgentIdentity(state.agentId, state.agentName) { phone.drawerOpen = true }
    }
    if (fullScreen) {
        PhoneChatHeader(
            identity = identity,
            onMenu = { phone.drawerOpen = true },
            onCanvas = inputs.onBackToCanvas,
            onHeightChange = onHeightChange,
        )
    } else {
        PhoneCanvasIdentityPill(identity, onHeightChange = onHeightChange)
    }
}

/**
 * letta-mobile-vgouv: the shared pill's identity on the phone preview. The agents panel (the
 * drawer, which holds the rail) is its switcher; the desktop keeps no agent favourite or pin, so
 * neither mark shows and a long press does nothing.
 */
internal fun phoneAgentIdentity(agentId: String?, agentName: String?, onSwitch: () -> Unit): AgentIdentity =
    AgentIdentity(
        agentId = agentId.orEmpty(),
        name = agentName?.takeIf(String::isNotBlank) ?: "Chat",
        isFavorite = false,
        isPinned = false,
        onClick = onSwitch,
        onLongClick = {},
    )

/** The status bar, plus the floating header (full-screen page) or the canvas mode's agent pill. */
@Composable
private fun phoneTopChromeInset(fullScreen: Boolean, headerHeight: Dp): Dp {
    val density = LocalDensity.current
    val statusBar = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    // The canvas mode reserves the room its pill takes (status bar, the pill and its gaps), so the board starts below it.
    return if (fullScreen) maxOf(statusBar, headerHeight) else statusBar + headerHeight + LettaDimens.Space.sm * 2
}
