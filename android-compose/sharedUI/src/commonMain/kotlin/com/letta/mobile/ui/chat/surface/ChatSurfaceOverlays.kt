package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.surface.timeline.A2uiSnackbarEffect
import com.letta.mobile.ui.chat.surface.timeline.ErrorSnackbarEffect
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.OverlayPresentation

/**
 * letta-mobile-bglj6.1 / ckmub.3.15 (hard cut S13): the page's transient messages, queued on the
 * window's [OverlayHost] in every mode. Chat errors and A2UI action results reach the user whether
 * the timeline is on screen or the chat is docked under the canvas; each is queued once and then
 * acknowledged to the owner. The page's [com.letta.mobile.ui.host.OverlaySnackbarSlot] draws the
 * snackbar above its composer.
 */
@Composable
internal fun ChatSurfaceOverlays(uiState: ChatUiState, port: ChatSessionPort, overlays: OverlayHost) {
    val actions = port.actions
    A2uiSnackbarEffect(uiState.a2uiActionSnackbar, overlays, actions)
    ErrorSnackbarEffect(uiState.error, overlays, actions)
    val recompile by port.recompileStatus.collectForChatSurface()
    RecompileSnackbarEffect(recompile, overlays, actions)
}

/**
 * letta-mobile-zxk2n.5 (G92): `/recompile`'s states. "Recompiling" stays up while the command is
 * queued; the answer replaces it (an error stays longer), and once queued it is acknowledged.
 */
@Composable
internal fun RecompileSnackbarEffect(status: RecompileStatus, overlays: OverlayHost, actions: ChatActions) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(status, overlays) {
        val message = status.message() ?: return@LaunchedEffect
        val queued = status == RecompileStatus.Queued
        val presentation = when {
            queued -> OverlayPresentation(durationMillis = null)
            status.isError -> OverlayPresentation.Long
            else -> OverlayPresentation.Short.copy(dismissible = true)
        }
        overlays.show(Overlay.Snackbar(message, key = RECOMPILE_KEY, presentation = presentation))
        if (!queued) currentActions.dismissRecompileStatus()
    }
}

private const val RECOMPILE_KEY = "chat-recompile"
