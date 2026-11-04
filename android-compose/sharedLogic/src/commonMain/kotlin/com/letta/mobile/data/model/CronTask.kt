package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a scheduled prompt. The App Server's native cron commands own it,
 * reached through admin_rpc (`CronAdminHandlers`); the retired admin-shim's
 * `lib/types/crons.ts` was the original shape and is no longer authoritative.
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
    const val PAUSED = "paused"
}
