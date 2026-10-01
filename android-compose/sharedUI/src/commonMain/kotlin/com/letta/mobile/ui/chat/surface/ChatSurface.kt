package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ChatComposerPanel
import com.letta.mobile.ui.chat.surface.timeline.ChatTimeline

/**
 * letta-mobile-bglj6.1: the ONE chat page, shared by Android and desktop.
 *
 * It renders [port] in the presentation's [ChatSurfaceMode]: a bar docked at the bottom of
 * the canvas, the full-screen page, or (reserved) a floating panel. Every mode binds to the
 * same [port], so the draft, queued follow-ups and the run are the same object in each.
 *
 * @param presentation where the page is drawn; owned by the host, changed through [onIntent].
 * @param onIntent raises a mode transition. The host reduces it with ChatSurfaceModeReducer.
 * @param pagedTimeline the canonical paged timeline when the owner has one; otherwise the
 *   timeline renders `ChatUiState.messages`.
 */
@Composable
fun ChatSurface(
    port: ChatSessionPort,
    presentation: ChatSurfacePresentation,
    onIntent: (ChatSurfaceIntent) -> Unit,
    host: ChatSurfaceHost,
    modifier: Modifier = Modifier,
    appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
    pagedTimeline: CanonicalTimelinePresentation? = null,
) {
    val uiState by port.uiState.collectAsState()
    val composer by port.composer.collectAsState()
    when (presentation.mode) {
        ChatSurfaceMode.FullScreen -> Column(modifier.fillMaxSize()) {
            ChatTimeline(
                state = uiState,
                pagedTimeline = pagedTimeline,
                actions = port.actions,
                capabilities = port.capabilities,
                host = host,
                appearance = appearance,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ChatComposerPanel(
                composer = composer,
                uiState = uiState,
                actions = port.actions,
                capabilities = port.capabilities,
                host = host,
                platform = platform,
                mode = presentation.mode,
                onIntent = onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ChatSurfaceMode.Docked, ChatSurfaceMode.Floating -> Box(modifier) {
            ChatComposerPanel(
                composer = composer,
                uiState = uiState,
                actions = port.actions,
                capabilities = port.capabilities,
                host = host,
                platform = platform,
                mode = presentation.mode,
                onIntent = onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
