package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.sentruns.IdentifiedChatSend
import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * The app's chat session as a [VoiceChatSession].
 *
 * The rows and the send queue are the chat page's ([ChatSessionPort.uiState]); the identity of each send and the runs
 * the sends started come from [sender] ([IdentifiedChatSend]); [conversation] is the conversation
 * the sends go to, so a switch ends a reply as cancelled; stopping is the page's own Stop
 * ([com.letta.mobile.ui.chat.session.ChatActions.stopRun]). The state is read off those flows with
 * no scope or cache of its own, so its value is always current. Building one starts nothing.
 */
class ChatVoiceChatSession(
    private val page: ChatSessionPort,
    private val sender: IdentifiedChatSend,
    private val conversation: StateFlow<ConversationId?>,
) : VoiceChatSession {
    override val state: StateFlow<VoiceChatState> = DerivedStateFlow(
        combine(page.uiState, sender.sentRuns, conversation) { ui, runs, current ->
            voiceChatState(ui.messages, runs, current, ui.sendQueue)
        },
    ) { current() }

    private fun current(): VoiceChatState =
        page.uiState.value.let { ui -> voiceChatState(ui.messages, sender.sentRuns.value, conversation.value, ui.sendQueue) }

    /** Sends [text]; throws [ChatSendRefusedException] when the session refused it before enqueuing. */
    override suspend fun send(text: VoiceText): SentUtterance {
        val id = sender.sendIdentified(text.value) ?: throw ChatSendRefusedException()
        return SentUtterance(ClientMessageId(id.value))
    }

    override fun stopRun() {
        page.actions.stopRun()
    }
}

/** The chat session refused a message before enqueuing it, so it has no identity to follow. */
class ChatSendRefusedException : IllegalStateException("The chat session refused the message")
