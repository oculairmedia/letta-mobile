package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance

/**
 * letta-mobile-bglj6.1: the shared chat timeline: status/empty/welcome states, the message
 * list (paged when [pagedTimeline] is non-null, else `state.messages`), and every row.
 */
@Composable
internal fun ChatTimeline(
    state: ChatUiState,
    pagedTimeline: CanonicalTimelinePresentation?,
    actions: ChatActions,
    capabilities: ChatSurfaceCapabilities,
    host: ChatSurfaceHost,
    appearance: ChatSurfaceAppearance,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
