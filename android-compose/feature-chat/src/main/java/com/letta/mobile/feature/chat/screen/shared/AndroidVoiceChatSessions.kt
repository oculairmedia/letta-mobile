package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.chat.ChatVoiceChatSession
import com.letta.mobile.data.voice.responder.chat.canonicalLiveRows
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.map

/**
 * Voice mode's chat on Android: the shared page's [port] with the view model's identified sends,
 * bound to the conversation the view model sends to (re-read whenever the page's state changes, so
 * a new chat's first send that creates the conversation is seen). The page's message list does not
 * hold the conversation's rows on Android (the shared page draws the canonical presentation
 * directly), so the reply in flight is read from that presentation's live overlay as it streams.
 */
internal class AndroidVoiceChatSessions(
    private val viewModel: AdminChatViewModel,
    private val port: ChatSessionPort,
) : VoiceChatSessions {
    private val conversation = DerivedStateFlow(port.uiState.map { viewModel.conversationId }) { viewModel.conversationId }
    private val canonical = DerivedStateFlow(viewModel.pagingPresentation.map { it?.canonical }) {
        viewModel.pagingPresentation.value?.canonical
    }
    private val liveRows = canonicalLiveRows(canonical)

    override fun open(): VoiceChatSession = ChatVoiceChatSession(port, viewModel.identifiedSend, conversation, liveRows)
}
