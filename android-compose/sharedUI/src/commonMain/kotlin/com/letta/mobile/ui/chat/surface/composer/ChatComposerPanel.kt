package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform

/**
 * letta-mobile-bglj6.1: the shared composer: queued sends, the prompt card, autocomplete,
 * attachments, model/context/working-directory chrome, send and stop.
 *
 * In [ChatSurfaceMode.Docked] it is the canvas's chat bar; in [ChatSurfaceMode.FullScreen]
 * it is the page's composer, where swipe-up on the prompt card raises
 * [ChatSurfaceIntent.OpenCanvas].
 */
@Composable
internal fun ChatComposerPanel(
    composer: ChatComposerUiState,
    uiState: ChatUiState,
    actions: ChatActions,
    capabilities: ChatSurfaceCapabilities,
    host: ChatSurfaceHost,
    platform: ChatSurfacePlatform,
    mode: ChatSurfaceMode,
    onIntent: (ChatSurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
