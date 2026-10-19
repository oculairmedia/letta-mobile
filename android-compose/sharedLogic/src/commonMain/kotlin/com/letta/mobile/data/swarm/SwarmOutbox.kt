package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import kotlinx.serialization.Serializable

/**
 * How far one outbox entry got. The order matters: an entry is re-sent only
 * while it is [Pending] (its input never left this machine). From [Sending] on, the input may have
 * reached the host, and the server does not dedupe by `client_message_id`, so the entry is resumed
 * by reading the history, never sent again. A [Delivered] entry is kept as a tombstone for the
 * time to live, so reusing its id resumes (reads the reply back) instead of sending a second copy.
 */
@Serializable
enum class OutboxStage { Pending, Sending, Accepted, Persisted, Delivered }

/**
 * One message waiting for delivery, written BEFORE anything is dialled (alink-style outbox). The
 * conversation a `--new` send created is recorded as soon as it exists, so a retry reuses it
 * instead of creating another. [anchor] is the conversation's newest message before the input
 * left: the persisted message is looked for after it.
 */
@Serializable
data class OutboxEntry(
    val id: ClientMessageId,
    val agentRef: AgentRef,
    val target: ConversationTarget,
    val text: String,
    val createdAtMs: Long,
    val stage: OutboxStage = OutboxStage.Pending,
    val agentId: AgentId? = null,
    val conversation: ConversationId? = null,
    val anchor: MessageId? = null,
    val persistedAs: MessageId? = null,
    val attempts: Int = 0,
) {
    /** A resumed id must name the same message it was first used for. */
    fun sameMessageAs(other: OutboxEntry): Boolean =
        agentRef == other.agentRef && target == other.target && text == other.text

    fun isPastSending(): Boolean = stage != OutboxStage.Pending
}

/** The durable outbox seam: one entry per [ClientMessageId]. */
interface SwarmOutbox {
    suspend fun get(id: ClientMessageId): OutboxEntry?

    /** Writes [entry] durably before returning. */
    suspend fun put(entry: OutboxEntry)

    suspend fun remove(id: ClientMessageId)

    suspend fun list(): List<OutboxEntry>
}
