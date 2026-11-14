package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.sentruns.SentMessageRuns
import com.letta.mobile.data.chat.sentruns.SentRunId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.VoiceChatRow
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceConversationKey
import com.letta.mobile.data.voice.responder.VoiceRowKey
import com.letta.mobile.data.voice.responder.VoiceRowKind
import com.letta.mobile.data.voice.responder.VoiceRunId

/**
 * The chat page's rows and send queue, the session's sent-message runs and its conversation, as the
 * voice responder reads them. A queued message's id is the otid it is sent with once it runs; one the
 * App Server parked behind another client's turn counts as queued too.
 */
internal fun voiceChatState(
    messages: List<UiMessage>,
    runs: SentMessageRuns,
    conversation: ConversationId?,
    queue: ConversationSendQueue = ConversationSendQueue(),
): VoiceChatState = VoiceChatState(
    conversation = conversation?.let { VoiceConversationKey(it.value) },
    rows = messages.map(::voiceChatRow),
    startedRuns = runs.started.entries.associate { (id, ids) -> ClientMessageId(id.value) to ids.toVoiceRuns() },
    activeRuns = runs.active.toVoiceRuns(),
    failedRuns = runs.failed.toVoiceRuns(),
    queued = (queue.items + listOfNotNull(queue.queuedOnServer)).mapTo(mutableSetOf()) { ClientMessageId(it.id.value) },
)

private fun Set<SentRunId>.toVoiceRuns(): Set<VoiceRunId> = mapTo(mutableSetOf()) { VoiceRunId(it.value) }

/**
 * One chat row. Its key is stable while its text streams and when its run id is promoted: a
 * message this client sent is keyed by its client message id, which survives the pending row's
 * confirmation; every other row by its own id. Only a user row carries a client message id.
 */
internal fun voiceChatRow(message: UiMessage): VoiceChatRow {
    val kind = voiceRowKind(message)
    val sentAs = message.clientMessageId?.takeIf { kind == VoiceRowKind.USER && it.isNotBlank() }
    return VoiceChatRow(
        key = rowKey(message, sentAs),
        kind = kind,
        text = VoiceText(message.content),
        clientMessageId = sentAs?.let(::ClientMessageId),
        runId = message.runId?.takeIf { it.isNotBlank() }?.let(::VoiceRunId),
    )
}

private fun rowKey(message: UiMessage, sentAs: String?): VoiceRowKey {
    if (sentAs != null) return VoiceRowKey(SENT_KEY_PREFIX + sentAs)
    return VoiceRowKey(ROW_KEY_PREFIX + message.id)
}

/** Errors, the person's plain messages and the agent's plain text; reasoning, tools, approvals and the rest are OTHER. */
internal fun voiceRowKind(message: UiMessage): VoiceRowKind {
    if (message.isError) return VoiceRowKind.ERROR
    if (!message.isPlainText()) return VoiceRowKind.OTHER
    return when (message.role) {
        USER_ROLE -> VoiceRowKind.USER
        ASSISTANT_ROLE -> VoiceRowKind.ASSISTANT_TEXT
        else -> VoiceRowKind.OTHER
    }
}

private fun UiMessage.isPlainText(): Boolean {
    if (isReasoning || !toolCalls.isNullOrEmpty()) return false
    return listOf(approvalRequest, approvalResponse, generatedUi, subagentNotification).all { it == null }
}

private const val USER_ROLE = "user"
private const val ASSISTANT_ROLE = "assistant"
private const val SENT_KEY_PREFIX = "sent:"
private const val ROW_KEY_PREFIX = "row:"
