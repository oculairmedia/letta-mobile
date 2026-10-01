package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.surface.ChatCanvasActions

/** letta-mobile-bglj6.1: which conversation's board the shared chat page docks under. */
@Immutable
data class ChatCanvasTarget(
    val agentId: String,
    val conversationId: String?,
)

/**
 * letta-mobile-bglj6.1: the app's canvas, drawn by the shared chat page in its docked mode.
 *
 * The canvas screen lives in `:app`, which feature-chat cannot depend on, so the app provides
 * it through [LocalChatCanvasSlot]. Without one (tests, previews) the page has no canvas and
 * falls back to the full-screen chat with canvas navigation.
 *
 * The canvas runs edge to edge under the chat screen's status bar and floating header;
 * `chromeTopInset` is their height, which the canvas's own chrome (its actions pill) keeps below.
 */
@Immutable
class ChatCanvasSlot(
    val content: @Composable (target: ChatCanvasTarget, actions: ChatCanvasActions, chromeTopInset: Dp) -> Unit,
)

val LocalChatCanvasSlot = staticCompositionLocalOf<ChatCanvasSlot?> { null }
