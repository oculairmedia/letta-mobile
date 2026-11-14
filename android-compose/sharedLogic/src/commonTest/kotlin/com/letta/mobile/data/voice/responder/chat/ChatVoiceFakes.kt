package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.sentruns.IdentifiedChatSend
import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.chat.sentruns.SentMessageRuns
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.FakeChatSessionPort
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A chat page whose rows the test sets and whose Stop presses are counted (and run [onStop]). */
internal class FakeChatPage : ChatSessionPort {
    private val ui = MutableStateFlow(ChatUiState())
    private val base = FakeChatSessionPort()
    var stops = 0
        private set
    var onStop: () -> Unit = {}

    override val uiState: StateFlow<ChatUiState> = ui
    override val composer: StateFlow<ChatComposerUiState> = base.composer
    override val actions: ChatActions = object : ChatActions by base.actions {
        override fun stopRun() {
            stops++
            onStop()
        }
    }

    val messages: List<UiMessage> get() = ui.value.messages

    fun show(vararg rows: UiMessage) = ui.update { it.copy(messages = rows.toList().toPersistentList()) }

    fun add(vararg rows: UiMessage) = ui.update { it.copy(messages = (it.messages + rows).toPersistentList()) }

    /** Shows [queue] as the conversation's send queue, as the chat page does with its host's queue. */
    fun showQueue(queue: ConversationSendQueue) = ui.update { it.copy(sendQueue = queue) }

    /** Replaces the row with [row]'s id (or the user row with its client id) in place. */
    fun replace(row: UiMessage) = ui.update { state ->
        val next = state.messages.map { if (sameRow(it, row)) row else it }
        state.copy(messages = next.toPersistentList())
    }

    private fun sameRow(old: UiMessage, new: UiMessage): Boolean =
        old.id == new.id || (old.clientMessageId != null && old.clientMessageId == new.clientMessageId)
}

/** An identified sender whose ids the test scripts; [runs] is whatever publishes the sent runs. */
internal class FakeIdentifiedSend(
    private val runs: StateFlow<SentMessageRuns> = MutableStateFlow(SentMessageRuns()),
    private val onSend: (SentMessageId) -> Unit = {},
) : IdentifiedChatSend {
    val texts = mutableListOf<String>()
    var refuse = false
    override val sentRuns: StateFlow<SentMessageRuns> = runs

    override suspend fun sendIdentified(text: String): SentMessageId? {
        if (refuse) return null
        texts += text
        return SentMessageId("c${texts.size}").also(onSend)
    }
}

/** The id a chat row is keyed by on the page. */
internal data class RowId(val value: String)

/** The run a chat row was streamed on. */
internal data class RowRun(val value: String)

/** A user row; [sentAs] is the otid this client sent it under, when it is this client's message. */
internal fun userRow(id: RowId, text: String, sentAs: SentMessageId? = null) =
    UiMessage(id = id.value, role = "user", content = text, timestamp = "t", clientMessageId = sentAs?.value)

internal fun assistantRow(id: RowId, text: String, run: RowRun?) =
    UiMessage(id = id.value, role = "assistant", content = text, timestamp = "t", runId = run?.value)

/** [userRow] by plain ids, for the voice-mode tests that name rows and otids as strings. */
internal fun userRow(id: String, text: String, sentAs: String? = null) =
    userRow(RowId(id), text, sentAs?.let(::SentMessageId))

/** [assistantRow] by plain ids, for the voice-mode tests that name rows and runs as strings. */
internal fun assistantRow(id: String, text: String, run: String?) = assistantRow(RowId(id), text, run?.let(::RowRun))
