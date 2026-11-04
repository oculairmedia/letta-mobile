package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.lanes.ConversationPurpose
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ReadOnlySubagentSessionPort
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import androidx.compose.runtime.State
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.components.LocalConversationLanes
import com.letta.mobile.ui.components.LocalConversationPurposes

/** Read acknowledgement is tied to rendered, loaded transcript identities, never to row selection. */
@Composable
internal fun AcknowledgeLaneTranscript(state: State<ChatUiState>) {
    val owner = LocalConversationLanes.current ?: return
    val ui = state.value
    val conversation = (ui.conversationState as? ConversationState.Ready)?.conversationId ?: return
    val agent = ui.agentId ?: return
    if (ui.isLoadingMessages) return
    val ids = ui.messages.mapTo(mutableSetOf()) { it.id }
    // A source update after the transcript mounted must also be acknowledged if already displayed.
    val activity = owner.activity.collectForChatSurface().value[conversation]
    LaunchedEffect(owner, conversation, agent, ids, activity) {
        owner.displayed(conversation, agent, ids)
    }
}

/** Reuses the existing read-only transcript port for work references in every chat presentation. */
@Composable
internal fun rememberLaneTranscriptPort(port: ChatSessionPort, recents: ChatRecentInteractions?): ChatSessionPort {
    val state = port.uiState.collectForChatSurface().value
    val id = (state.conversationState as? ConversationState.Ready)?.conversationId
    val purposes = LocalConversationPurposes.current
    val lane = recents?.conversations?.firstOrNull { it.id == id }?.lane ?: id?.let(purposes::get)
    val work = lane?.purpose == ConversationPurpose.WORK
    val scope = rememberCoroutineScope()
    return remember(port, work, scope) {
        if (work) ReadOnlySubagentSessionPort(port, "Work record · View only", scope) else port
    }
}
