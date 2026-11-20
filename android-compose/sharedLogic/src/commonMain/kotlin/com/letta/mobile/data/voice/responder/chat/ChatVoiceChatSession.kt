package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.projection.chronologicalMessages
import com.letta.mobile.data.chat.sentruns.IdentifiedChatSend
import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The app's chat session as a [VoiceChatSession].
 *
 * The rows and the send queue are the chat page's ([ChatSessionPort.uiState]); the identity of each send and the runs
 * the sends started come from [sender] ([IdentifiedChatSend]); [conversation] is the conversation
 * the sends go to, so a switch ends a reply as cancelled; stopping is the page's own Stop
 * ([com.letta.mobile.ui.chat.session.ChatActions.stopRun]). The state is read off those flows with
 * no scope or cache of its own, so its value is always current. Building one starts nothing.
 *
 * [liveRows] are rows a host streams outside the page's message list: on the canonical route
 * (desktop and Android) the reply in flight lives in the presentation's live overlay, not in
 * [ChatSessionPort.uiState], and grows there frame by frame ([canonicalLiveRows]). They join the
 * page's rows (a row the page already has, by id, is not repeated), so the reply is followed, and
 * spoken sentence by sentence, while it streams.
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

    /** The agent answering the latest send, captured when that send started its reply; null before any. */
    private val answering = MutableStateFlow<String?>(null)

    /**
     * Sends [text]; throws [ChatSendRefusedException] when the session refused it before enqueuing.
     * The page's agent is captured as the one answering before the send goes out, so the reply is
     * spoken in that agent's voice even if the page moves to another agent while it plays.
     */
    override suspend fun send(text: VoiceText): SentUtterance {
        val previous = answering.value
        answering.value = page.uiState.value.agentId?.takeIf { it.isNotBlank() }
        val id = sender.sendIdentified(text.value) ?: run {
            answering.value = previous
            throw ChatSendRefusedException()
        }
        return SentUtterance(ClientMessageId(id.value))
    }

    override fun stopRun() {
        page.actions.stopRun()
    }

    /** The agent answering the reply in progress, as captured by its [send]; never the page's agent read now. */
    override val agentId: String? get() = answering.value
}

/**
 * The rows of the reply in flight that the canonical [presentation] draws in its live overlay, in
 * chronological order: every streamed frame of the selected presentation, and nothing while there
 * is none. Reads the presentations' own state only, so it needs no scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun canonicalLiveRows(presentation: StateFlow<CanonicalTimelinePresentation?>): StateFlow<List<UiMessage>> = DerivedStateFlow(
    presentation.flatMapLatest { it?.live ?: flowOf(emptyList()) }.map(::chronologicalMessages),
) { presentation.value?.live?.value?.let(::chronologicalMessages).orEmpty() }

/** The page's [rows] followed by the [live] rows it does not hold yet (by id). */
internal fun withLiveRows(rows: List<UiMessage>, live: List<UiMessage>): List<UiMessage> {
    if (live.isEmpty()) return rows
    val known = rows.mapTo(HashSet()) { it.id }
    return rows + live.filter { it.id !in known }
}

/** The chat session refused a message before enqueuing it, so it has no identity to follow. */
class ChatSendRefusedException : IllegalStateException("The chat session refused the message")
