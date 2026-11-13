package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.sentruns.IdentifiedChatSend
import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.flow.MutableStateFlow
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
 *
 * [liveRows] are rows a host streams outside the page's message list: the desktop's canonical
 * route keeps the reply in flight in its live overlay, not in [ChatSessionPort.uiState]. They join
 * the page's rows (a row the page already has, by id, is not repeated), so the reply is followed
 * wherever the host draws it.
 */
class ChatVoiceChatSession(
    private val page: ChatSessionPort,
    private val sender: IdentifiedChatSend,
    private val conversation: StateFlow<ConversationId?>,
    private val liveRows: StateFlow<List<UiMessage>> = MutableStateFlow(emptyList()),
) : VoiceChatSession {
    override val state: StateFlow<VoiceChatState> = DerivedStateFlow(
        combine(page.uiState, liveRows, sender.sentRuns, conversation) { ui, live, runs, current ->
            voiceChatState(withLiveRows(ui.messages, live), runs, current, ui.sendQueue)
        },
    ) { current() }

    private fun current(): VoiceChatState = page.uiState.value.let { ui ->
        voiceChatState(withLiveRows(ui.messages, liveRows.value), sender.sentRuns.value, conversation.value, ui.sendQueue)
    }

    /** Sends [text]; throws [ChatSendRefusedException] when the session refused it before enqueuing. */
    override suspend fun send(text: VoiceText): SentUtterance {
        val id = sender.sendIdentified(text.value) ?: throw ChatSendRefusedException()
        return SentUtterance(ClientMessageId(id.value))
    }

    override fun stopRun() {
        page.actions.stopRun()
    }
}

/** The page's [rows] followed by the [live] rows it does not hold yet (by id). */
internal fun withLiveRows(rows: List<UiMessage>, live: List<UiMessage>): List<UiMessage> {
    if (live.isEmpty()) return rows
    val known = rows.mapTo(HashSet()) { it.id }
    return rows + live.filter { it.id !in known }
}

/** The chat session refused a message before enqueuing it, so it has no identity to follow. */
class ChatSendRefusedException : IllegalStateException("The chat session refused the message")
