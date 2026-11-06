package com.letta.mobile.data.lanes

import kotlinx.coroutines.flow.Flow

/**
 * Committed inbox arrivals for one session. The producer is mr49w's capability-gated
 * `conversation_activity` frame (`milestone: "persisted"`): its adapter must forward only that
 * milestone, after the transport's own authentication and grant checks. Metadata
 * `conversation_updated` invalidations and streaming deltas are never arrivals.
 */
fun interface LaneActivitySource {
    /** Whether the host currently advertises this producer. */
    val available: Boolean get() = true

    fun arrivals(): Flow<LaneArrival>
}

/**
 * One persisted message in [conversation], owned by [agent]. [sourceMessageId] is the committed
 * transcript identity a rendered transcript shows; it says nothing about forwarded provenance.
 */
data class LaneArrival(
    val agent: String,
    val conversation: String,
    val sourceMessageId: String,
)

/** A projection never equates an unbound producer with zero unread. */
enum class LaneActivityAvailability { AVAILABLE, UNAVAILABLE }

/** What a row shows for one conversation; [incomplete] means more arrived than are retained. */
data class LaneActivity(
    val availability: LaneActivityAvailability = LaneActivityAvailability.UNAVAILABLE,
    val unreadCount: Int? = null,
    val incomplete: Boolean = false,
)
