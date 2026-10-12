package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.surface.timeline.A2uiSnackbarEffect
import com.letta.mobile.ui.chat.surface.timeline.ErrorSnackbarEffect

/**
 * letta-mobile-bglj6.1: the page's one snackbar host, fed in every mode. Chat errors and A2UI
 * action results reach the user whether the timeline is on screen or the chat is docked under
 * the canvas; each is shown once and then acknowledged to the owner.
 */
@Composable
internal fun rememberChatSurfaceSnackbars(uiState: ChatUiState, port: ChatSessionPort): SnackbarHostState {
    val snackbars = remember { SnackbarHostState() }
    val actions = port.actions
    A2uiSnackbarEffect(uiState.a2uiActionSnackbar, snackbars, actions)
    ErrorSnackbarEffect(uiState.error, snackbars, actions)
    val recompile by port.recompileStatus.collectForChatSurface()
    RecompileSnackbarEffect(recompile, snackbars, actions)
    return snackbars
}

/**
 * letta-mobile-zxk2n.5 (G92): `/recompile`'s states. "Recompiling" stays up while the command is
 * queued; the answer replaces it (an error stays longer), and once shown it is acknowledged.
 */
@Composable
internal fun RecompileSnackbarEffect(status: RecompileStatus, snackbars: SnackbarHostState, actions: ChatActions) {
    LaunchedEffect(status) {
        val message = status.message() ?: return@LaunchedEffect
        val queued = status == RecompileStatus.Queued
        snackbars.currentSnackbarData?.dismiss()
        val duration = when {
            queued -> SnackbarDuration.Indefinite
            status.isError -> SnackbarDuration.Long
            else -> SnackbarDuration.Short
        }
        snackbars.showSnackbar(message = message, withDismissAction = !queued, duration = duration)
        if (!queued) actions.dismissRecompileStatus()
    }
}
