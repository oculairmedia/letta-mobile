package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.chat.ChatVoiceChatSession
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.map

/**
 * Voice mode's chat on Android: the shared page's [port] with the view model's identified sends,
 * bound to the conversation the view model sends to (re-read whenever the page's state changes, so
 * a new chat's first send that creates the conversation is seen).
 */
internal class AndroidVoiceChatSessions(
    private val viewModel: AdminChatViewModel,
    private val port: ChatSessionPort,
) : VoiceChatSessions {
    private val conversation = DerivedStateFlow(port.uiState.map { viewModel.conversationId }) { viewModel.conversationId }

    override fun open(): VoiceChatSession = ChatVoiceChatSession(port, viewModel.identifiedSend, conversation)
}
