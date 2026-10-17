package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.session.SubagentTimelineRoute
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.session.TimelineSubagentViewSessions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * letta-mobile-zxk2n.8: where the shared subagent dual view reads a child's conversation on Android -
 * the canonical transport the chat's own timeline reads. Only binds: the session, its port and the
 * read-only composer are the shared ones.
 */
@HiltViewModel
internal class SharedChatSubagentViewsViewModel @Inject constructor(
    transport: TimelineTransport,
) : ViewModel() {
    val sessions: SubagentViewSessions = TimelineSubagentViewSessions { view -> SubagentTimelineRoute(transport, view.conversationId) }
}

/** The dual view's sessions, or null outside a Hilt activity (previews, tests): "view conversation" then navigates. */
@Composable
internal fun rememberSharedChatSubagentViewSessions(): SubagentViewSessions? {
    val activity = LocalContext.current.findActivity()
    if (activity !is dagger.hilt.internal.GeneratedComponentManager<*>) return null
    return hiltViewModel<SharedChatSubagentViewsViewModel>().sessions
}
