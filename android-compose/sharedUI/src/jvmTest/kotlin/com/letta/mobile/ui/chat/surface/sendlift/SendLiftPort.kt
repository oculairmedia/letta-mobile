package com.letta.mobile.ui.chat.surface.sendlift

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-86njl.1: an owner that behaves like the real ones on a send. [ChatActions.send]
 * appends the draft as a pending prompt (`local-<n>` with otid `otid-<n>`) and clears the draft;
 * [rename] is the server's ack (new id, same otid); [reply] appends an assistant message.
 */
internal class SendLiftPort(
    history: Int,
    draft: String,
    /** Where a send goes when the page reads its timeline elsewhere (text, otid); null appends it to [uiState]. */
    private val onSend: ((String, String) -> Unit)? = null,
) : ChatSessionPort {
    private var sends = 0
    private val recorded = RecordingChatActions()

    override val uiState = MutableStateFlow(
        ChatUiState(
            conversationState = ConversationState.Ready("conv-1"),
            messages = seed(history).toPersistentList(),
            isLoadingMessages = false,
            agentName = "Meridian",
            agentId = AGENT,
        ),
    )
    override val composer = MutableStateFlow(ChatComposerUiState(text = draft, canSend = true))
    override val actions: ChatActions = object : ChatActions by recorded {
        override fun send() {
            recorded.send()
            val prompt = pendingPrompt(composer.value.text)
            if (onSend != null) onSend.invoke(prompt.content, otid) else append(prompt)
            composer.value = composer.value.copy(text = "")
        }
    }

    /** The otid of the prompt sent last. */
    val otid: String get() = "otid-$sends"

    /** The list key the sent prompt's row has (MessageGrouping: "msg-" + otid). */
    val promptKey: String get() = "msg-$otid"

    /** The server acknowledges the last prompt: its own id, the otid kept, no longer pending. */
    fun rename() = update { message ->
        if (message.clientMessageId == otid) message.copy(id = "srv-$sends", isPending = false) else message
    }

    /** The agent's reply to the last prompt, in a run of its own. */
    fun reply(text: String) = append(
        UiMessage(id = "reply-$sends", role = "assistant", content = text, timestamp = STAMP, runId = "run-$sends"),
    )

    /** The agent starts working: the companion row's trigger on the Touch page. */
    fun typing(on: Boolean) {
        uiState.value = uiState.value.copy(isAgentTyping = on)
    }

    private fun pendingPrompt(text: String): UiMessage {
        sends++
        return UiMessage(
            id = "local-$sends", role = "user", content = text, timestamp = STAMP,
            clientMessageId = otid, isPending = true,
        )
    }

    private fun append(message: UiMessage) {
        uiState.value = uiState.value.copy(messages = (uiState.value.messages + message).toPersistentList())
    }

    private fun update(change: (UiMessage) -> UiMessage) {
        uiState.value = uiState.value.copy(messages = uiState.value.messages.map(change).toPersistentList())
    }

    companion object {
        const val AGENT = "agent-1"
        private const val STAMP = "2026-09-30T18:01:00Z"

        /** [pairs] question and answer pairs, oldest first, ending on an assistant message. */
        private fun seed(pairs: Int): List<UiMessage> = (0 until pairs).flatMap { i ->
            listOf(
                UiMessage(id = "u$i", role = "user", content = "Question number $i", timestamp = STAMP, clientMessageId = "seed-u$i"),
                UiMessage(id = "a$i", role = "assistant", content = "Answer number $i, a sentence long.", timestamp = STAMP, runId = "seed-run-$i"),
            )
        }
    }
}
