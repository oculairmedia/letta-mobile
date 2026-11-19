package com.letta.mobile.desktop.data

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.chat.ChatVoiceChatSession
import com.letta.mobile.data.voice.responder.chat.canonicalLiveRows
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Voice mode's chat on the desktop: the shared page's [port] with the identified sends of the
 * selected conversation's send coordinator (one per agent, so it is looked up on every start),
 * bound to the conversation [controller] has selected. On the canonical route the reply in flight
 * is drawn from the presentation's live overlay, not from the port's message list, so those rows
 * are handed to the session too.
 */
internal class DesktopVoiceChatSessions(
    private val controller: DesktopChatController,
    private val port: ChatSessionPort,
) : VoiceChatSessions {
    private val conversation = DerivedStateFlow(controller.state.map { it.selectedConversationId?.let(::ConversationId) }) {
        controller.state.value.selectedConversationId?.let(::ConversationId)
    }

    private val liveRows: StateFlow<List<UiMessage>> = canonicalLiveRows(controller.canonicalPresentation)

    override fun open(): VoiceChatSession? {
        val sender = controller.selectedCanonicalCoordinator()?.identifiedSend ?: return null
        return ChatVoiceChatSession(port, sender, conversation, liveRows)
    }
}
