package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart

/**
 * Disk-backed store of optimistic user messages that the server may never
 * echo back.
 *
 * Android keeps pending rows in the canonical ledger (CanonicalPendingLocalStore) and no longer
 * implements this; the shared TimelineRepository (desktop, web) still takes one.
 * Tests use an in-memory fake.
 *
 * rows are owned by a [PendingLocalScope] — the backend,
 * the agent and the conversation — not by the conversation id alone.
 * `conv-default-*` / `default` ids are reused by every agent and every backend,
 * so a conversation-only key let an unsent message resurface in another
 * agent's or backend's default conversation.
 */
interface PendingLocalStore {
    /** Persist (or update) a pending local. The record carries its owning scope. */
    suspend fun save(record: PendingLocalRecord)

    /** Drop a pending local once the server confirms it (or the user cancels). */
    suspend fun delete(otid: String)

    /** Mark the exact persisted optimistic row failed and retain only the newest failure. */
    suspend fun markFailed(otid: String)

    /** Load every pending local owned by [scope], oldest first. */
    suspend fun load(scope: PendingLocalScope): List<PendingLocalRecord>
}

/**
 * Owner of a pending local row: (backendId, agentId?, conversationId) — the same
 * key shape as [com.letta.mobile.data.timeline.snapshot.TimelineScope].
 *
 * A null [backendId] or [agentId] means the writer could not know it (a loop built
 * without a timeline scope, or a legacy row written before schema v20). Reads treat
 * a null on the ROW side as "legacy, matches any value"; those legacy rows are purged
 * on the next backend switch, so the leniency lasts for one backend session only.
 */
data class PendingLocalScope(
    val backendId: String?,
    val conversationId: String,
    val agentId: String? = null,
)

data class PendingLocalRecord(
    val otid: String,
    val conversationId: String,
    val content: String,
    val attachments: List<MessageContentPart.Image>,
    val sentAt: TimelineInstant,
    val deliveryState: DeliveryState = DeliveryState.SENT,
    val backendId: String? = null,
    val agentId: String? = null,
)

/** No-op store used in tests / situations where persistence is undesired. */
object NoOpPendingLocalStore : PendingLocalStore {
    override suspend fun save(record: PendingLocalRecord) = Unit
    override suspend fun delete(otid: String) = Unit
    override suspend fun markFailed(otid: String) = Unit
    override suspend fun load(scope: PendingLocalScope): List<PendingLocalRecord> = emptyList()
}
