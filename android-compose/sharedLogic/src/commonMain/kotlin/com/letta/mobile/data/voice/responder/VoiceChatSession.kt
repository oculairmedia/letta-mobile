package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.voice.VoiceText
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline

/** The client-side identity of one sent message, chosen when it is sent and kept through confirmation. */
@JvmInline
value class ClientMessageId(
    /** The id (an otid in the chat timeline). */
    val value: String,
)

/** The id of one agent run, provisional (streaming) or durable (server). */
@JvmInline
value class VoiceRunId(
    /** The id. */
    val value: String,
)

/** A stable key for one conversation row, unchanged while its text streams and after its run id is promoted. */
@JvmInline
value class VoiceRowKey(
    /** The key. */
    val value: String,
)

/** The conversation (agent and thread) a voice chat session is bound to. */
@JvmInline
value class VoiceConversationKey(
    /** The key. */
    val value: String,
)

/** What a row is, as far as speaking it goes. */
enum class VoiceRowKind {
    /** The person's own message. */
    USER,

    /** Assistant text meant to be read: spoken. */
    ASSISTANT_TEXT,

    /** An error the run reported. */
    ERROR,

    /** Anything else (reasoning, tool rows, summaries): never spoken. */
    OTHER,
}

/** One conversation row as the voice responder sees it. Its [toString] carries no text. */
data class VoiceChatRow(
    /** Stable row key. */
    val key: VoiceRowKey,
    /** What the row is. */
    val kind: VoiceRowKind,
    /** The row's text. */
    val text: VoiceText,
    /** The client message id of a message sent from this client; null otherwise. */
    val clientMessageId: ClientMessageId? = null,
    /** The run the row belongs to, provisional or durable; null when unknown. */
    val runId: VoiceRunId? = null,
)

/**
 * The conversation state the voice responder reads. The runs are keyed by identity, never by
 * position: [startedRuns] says which run each sent message started, [activeRuns] which runs are in
 * flight, [failedRuns] which failed. Contract: a run published in [startedRuns] is in [activeRuns]
 * from that moment until it ends, so a published run that is not active has finished.
 */
data class VoiceChatState(
    /** The conversation this state belongs to. */
    val conversation: VoiceConversationKey? = null,
    /** The rows, in conversation order. */
    val rows: List<VoiceChatRow> = emptyList(),
    /** For each sent message, every id (provisional and durable) of the run it started. */
    val startedRuns: Map<ClientMessageId, Set<VoiceRunId>> = emptyMap(),
    /** The runs in flight. */
    val activeRuns: Set<VoiceRunId> = emptySet(),
    /** The runs that failed. */
    val failedRuns: Set<VoiceRunId> = emptySet(),
)

/** A message as sent: its client message id. */
data class SentUtterance(
    /** The identity the session will use for the message and its run. */
    val id: ClientMessageId,
)

/**
 * The chat session as the voice responder needs it: sending returns the message's identity, and
 * the state publishes the run each sent message started.
 *
 * No adapter from the app's chat session port exists yet, deliberately: one cannot be written
 * correctly until the chat session itself (1) returns the client message id from a send (today
 * `sendText` returns nothing; the id is chosen internally as the otid in
 * `data/chat/send/ConversationTurnState.kt`), and (2) publishes, per sent message, the provisional
 * and durable ids of the run it started (today the pairing exists only inside
 * `data/chat/send/TurnIdentityLifecycle.kt` and is not on the chat state). Any adapter built
 * without those would have to guess, and guessing speaks other messages' replies.
 */
interface VoiceChatSession {
    /** The conversation state. */
    val state: StateFlow<VoiceChatState>

    /** Sends [text] and returns its identity. */
    suspend fun send(text: VoiceText): SentUtterance

    /** Asks the session to stop the run in flight. */
    fun stopRun()
}
