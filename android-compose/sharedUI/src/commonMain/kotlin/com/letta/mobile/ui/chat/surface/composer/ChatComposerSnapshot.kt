package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * letta-mobile-bglj6.1.9: the shared composer on its own, drawn for [mode] in [appearance]'s
 * idiom exactly as the page draws it. For screenshot comparisons against the legacy Android
 * composer and for previews; hosts render the whole page through ChatSurface instead.
 */
@Composable
fun ChatComposerSnapshot(
    composer: ChatComposerUiState,
    state: ChatUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
    host: ChatSurfaceHost = ChatSurfaceHost(),
    appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
    capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
) {
    CompositionLocalProvider(LocalChatPlatformStyle provides appearance.platformStyle) {
        ChatComposerPanel(
            composer = composer,
            uiState = state,
            actions = actions,
            capabilities = capabilities,
            host = host,
            platform = platform,
            mode = mode,
            onIntent = {},
            modifier = modifier,
        )
    }
}
