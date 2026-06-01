package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import java.time.Instant

/**
 * Disk-backed store of optimistic user messages that the server may never
 * echo back (mge5.24).
 *
 * Concrete production impl: [com.letta.mobile.data.local.RoomPendingLocalStore].
 * Tests use an in-memory fake.
 *
 * The store is conversation-scoped at the API surface so the timeline loop
 * doesn't need to reach across conversations.
 */
interface PendingLocalStore {
    /** Persist (or update) a pending local. */
    suspend fun save(record: PendingLocalRecord)

    /** Drop a pending local once the server confirms it (or the user cancels). */
    suspend fun delete(otid: String)

    /** Load every pending local for a conversation, oldest first. */
    suspend fun load(conversationId: String): List<PendingLocalRecord>
}

data class PendingLocalRecord(
    val otid: String,
    val conversationId: String,
    val content: String,
    val attachments: List<MessageContentPart.Image>,
    val sentAt: Instant,
)

/** No-op store used in tests / situations where persistence is undesired. */
object NoOpPendingLocalStore : PendingLocalStore {
    override suspend fun save(record: PendingLocalRecord) = Unit
    override suspend fun delete(otid: String) = Unit
    override suspend fun load(conversationId: String): List<PendingLocalRecord> = emptyList()
}
