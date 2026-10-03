package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance

/**
 * letta-mobile-bglj6.1: the shared timeline on its own (no composer, no page frame), drawn from
 * [state] exactly as the page draws it. For screenshot comparisons against the legacy Android
 * timeline and for previews; hosts render the whole page through ChatSurface instead.
 */
@Composable
fun ChatTimelineSnapshot(
    state: ChatUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
) {
    ChatTimeline(
        state = state,
        pagedTimeline = null,
        actions = actions,
        capabilities = ChatSurfaceCapabilities.Default,
        host = ChatSurfaceHost(),
        appearance = appearance,
        modifier = modifier,
    )
}
