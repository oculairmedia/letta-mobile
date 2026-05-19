package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-d52f.1: wire shape of a scheduled prompt as carried over
 * the shim's mobile WS cron protocol (sister to lcp-d5g). Mirrors the
 * shim's `lib/types/crons.ts` `CronTask` interface verbatim so the
 * persisted file shape and the wire shape stay in sync.
 *
 * Authoritative reference:
 *   /opt/stacks/letta-code-parallel/admin-shim/lib/types/crons.ts
 *
 * Status values: `active` (waiting to fire or due to repeat), `completed`
 * (one-shot fired or recurring exhausted), `cancelled` (deleted /
 * scheduler-stopped). The shim is the source of truth; mobile renders
 * whatever string arrives — `CronTaskStatus` exposes the documented
 * vocabulary as constants without forcing a closed enum (forward-compat
 * with future shim additions).
 *
 * Timestamps are ISO-8601 strings on the wire. Mobile does not parse them
 * eagerly at this layer; the UI helper / formatter converts when needed.
 */
@Serializable
data class CronTask(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("conversation_id") val conversationId: String,
    val name: String,
    val description: String,
    val cron: String,
    val timezone: String,
    val recurring: Boolean,
    val prompt: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("last_fired_at") val lastFiredAt: String? = null,
    @SerialName("fire_count") val fireCount: Int = 0,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    @SerialName("jitter_offset_ms") val jitterOffsetMs: Long = 0L,
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("fired_at") val firedAt: String? = null,
    @SerialName("missed_at") val missedAt: String? = null,
)

object CronTaskStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}
