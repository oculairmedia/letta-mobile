package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.surface.timeline.A2uiSnackbarEffect
import com.letta.mobile.ui.chat.surface.timeline.ErrorSnackbarEffect

/**
 * letta-mobile-bglj6.1: the page's one snackbar host, fed in every mode. Chat errors and A2UI
 * action results reach the user whether the timeline is on screen or the chat is docked under
 * the canvas; each is shown once and then acknowledged to the owner.
 */
@Composable
internal fun rememberChatSurfaceSnackbars(uiState: ChatUiState, actions: ChatActions): SnackbarHostState {
    val snackbars = remember { SnackbarHostState() }
    A2uiSnackbarEffect(uiState.a2uiActionSnackbar, snackbars, actions)
    ErrorSnackbarEffect(uiState.error, snackbars, actions)
    return snackbars
}
