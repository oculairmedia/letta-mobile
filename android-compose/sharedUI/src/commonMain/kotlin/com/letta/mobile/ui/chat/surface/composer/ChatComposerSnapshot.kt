package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle

/**
 * What [ChatComposerSnapshot] draws: the draft, the conversation's state and actions, and the
 * mode, host, idiom, platform and capabilities to draw them for.
 */
@Immutable
data class ComposerSnapshotContent(
    val composer: ChatComposerUiState,
    val state: ChatUiState,
    val actions: ChatActions,
    val mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
    val host: ChatSurfaceHost = ChatSurfaceHost(),
    val appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    val platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
    val capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
)

/**
 * letta-mobile-bglj6.1.9: the shared composer on its own, drawn for the content's mode in its
 * appearance's idiom exactly as the page draws it. For screenshot comparisons against the legacy
 * Android composer and for previews; hosts render the whole page through ChatSurface instead.
 */
@Composable
fun ChatComposerSnapshot(content: ComposerSnapshotContent, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalChatPlatformStyle provides content.appearance.platformStyle) {
        ChatComposerPanel(
            inputs = ComposerInputs(
                composer = content.composer,
                uiState = content.state,
                actions = content.actions,
                capabilities = content.capabilities,
                host = content.host,
                platform = content.platform,
                mode = content.mode,
                onIntent = {},
            ),
            modifier = modifier,
        )
    }
}
