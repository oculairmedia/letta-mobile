package com.letta.mobile.data.chat.sentruns

import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline

/** The client message id (the otid) a message from this client was sent under. */
@JvmInline
value class SentMessageId(
    /** The otid. */
    val value: String,
)

/** One id of an agent run: the provisional (streaming) id or the durable (server) id. */
@JvmInline
value class SentRunId(
    /** The id. */
    val value: String,
)

/**
 * Which run each message this client sent started, keyed by identity only.
 *
 * A run is attributed to a sent message only when the server pairs them: the echo of the message
 * carries its otid together with the run's id, every row of the turn the message started is
 * stamped with that otid as its turn id together with its run's id (a turn's run id rotates, a
 * tool step on one run and the reply on the next), and a later id of the same turn (the durable id
 * that replaces a provisional one) is added to the same entry. Nothing is inferred from text,
 * position or timing. A run in [started] is in [active] from the moment it is published until its
 * turn ends; when it ended as a failure its ids are also in [failed].
 */
data class SentMessageRuns(
    /** For each sent message, every id (provisional and durable) of the run it started. */
    val started: Map<SentMessageId, Set<SentRunId>> = emptyMap(),
    /** The ids of runs still in flight. */
    val active: Set<SentRunId> = emptySet(),
    /** The ids of runs that ended as a failure. */
    val failed: Set<SentRunId> = emptySet(),
)

/**
 * A chat session that says which message a send enqueued and which run that message started.
 *
 * Implemented by a host over its chat send pipeline; an owner that cannot name the message it
 * sent does not implement it.
 */
interface IdentifiedChatSend {
    /** The runs started by messages this client sent. */
    val sentRuns: StateFlow<SentMessageRuns>

    /**
     * Sends [text] the way the composer's direct send does and returns the id the message was
     * enqueued under, or null when the send was refused before anything was enqueued.
     */
    suspend fun sendIdentified(text: String): SentMessageId?
}

/** An [IdentifiedChatSend] over a send pipeline's published [sentRuns] and its identified [send]. */
internal class IdentifiedSendBinding(
    override val sentRuns: StateFlow<SentMessageRuns>,
    private val send: suspend (String) -> SentMessageId?,
) : IdentifiedChatSend {
    override suspend fun sendIdentified(text: String): SentMessageId? = send(text)
}
