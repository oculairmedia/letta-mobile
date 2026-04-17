package com.letta.mobile.data.timeline

import java.time.Instant
import java.util.UUID

/**
 * Matrix-inspired unified timeline event model.
 *
 * Each event carries a client-generated [otid] (transaction ID analog). Local
 * events represent optimistic sends that have not yet been confirmed by the
 * server. Confirmed events come from the server (either via streaming SSE or
 * via GET /messages).
 *
 * Both variants coexist in the timeline; on server echo, a Local event is
 * replaced by a Confirmed event with the same otid, preserving its position.
 *
 * See `docs/architecture/message-sync-migration.md` for design rationale.
 */
sealed class TimelineEvent {
    /** Monotonic ordering key. Stable across swaps. */
    abstract val position: Double

    /** Client- or server-generated transaction ID. Unique within a timeline. */
    abstract val otid: String

    /** Display content (plain text for user/assistant, formatted for tool/reasoning). */
    abstract val content: String

    /** Optimistic, not yet confirmed by server. Only ever role=USER in practice. */
    data class Local(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val role: Role = Role.USER,
        val sentAt: Instant,
        val deliveryState: DeliveryState,
    ) : TimelineEvent()

    /** Confirmed via server. */
    data class Confirmed(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val serverId: String,
        val messageType: TimelineMessageType,
        val date: Instant,
        val runId: String?,
        val stepId: String?,
    ) : TimelineEvent()
}

enum class Role { USER, ASSISTANT, SYSTEM }

enum class DeliveryState { SENDING, SENT, FAILED }

/**
 * Category of a timeline event. Distinct from the data-layer [com.letta.mobile.data.model.MessageType]
 * which enumerates every server message kind — this is the subset we display as discrete bubbles.
 */
enum class TimelineMessageType {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL,
    TOOL_RETURN,
    SYSTEM,
    OTHER,
}

/**
 * Per-conversation timeline. Events are always ordered by [TimelineEvent.position].
 *
 * Invariants (enforced in [init]):
 * - positions are strictly increasing
 * - otids are unique within the timeline
 *
 * All mutations return a new [Timeline] (immutable).
 */
data class Timeline(
    val conversationId: String,
    val events: List<TimelineEvent> = emptyList(),
    /** Highest seen server message id. Advances as stream/reconcile events arrive. */
    val liveCursor: String? = null,
    /** Cursor for backfill pagination (oldest known message id). */
    val backfillCursor: String? = null,
) {
    init {
        require(events.zipWithNext().all { (a, b) -> a.position < b.position }) {
            "Timeline events must be strictly ordered by position"
        }
        require(events.map { it.otid }.toSet().size == events.size) {
            "otids must be unique within a timeline"
        }
    }

    /** Next position for an append at the end. */
    fun nextLocalPosition(): Double {
        val last = events.lastOrNull()?.position ?: 0.0
        return last + 1.0
    }

    fun findByOtid(otid: String): TimelineEvent? = events.firstOrNull { it.otid == otid }

    /**
     * Append a new event at the end. Its position must be strictly greater than
     * the current last event's position (or the timeline must be empty).
     */
    fun append(event: TimelineEvent): Timeline {
        require(events.lastOrNull()?.let { it.position < event.position } ?: true) {
            "Appended event position ${event.position} must be > last ${events.lastOrNull()?.position}"
        }
        require(findByOtid(event.otid) == null) {
            "otid ${event.otid} already in timeline"
        }
        return copy(events = events + event)
    }

    /**
     * Replace a Local event (identified by otid) with a Confirmed event.
     * Preserves the Local's position to avoid visual jumps in the UI.
     *
     * If no Local with that otid is found (e.g., backfill case), inserts
     * the Confirmed event at its natural position instead.
     */
    fun replaceLocal(otid: String, confirmed: TimelineEvent.Confirmed): Timeline {
        val idx = events.indexOfFirst { it.otid == otid && it is TimelineEvent.Local }
        if (idx == -1) return insertOrdered(confirmed)
        val local = events[idx]
        val stabilized = confirmed.copy(position = local.position)
        val newEvents = events.toMutableList().also { it[idx] = stabilized }
        return copy(events = newEvents)
    }

    /**
     * Insert a Confirmed event at its correct ordered position.
     * Used for backfill (older messages loaded via pagination) or reconciling
     * missed stream events.
     *
     * Deduplicates by otid — if an event with the same otid already exists,
     * returns this timeline unchanged (keeps the existing, possibly Local, event).
     */
    fun insertOrdered(event: TimelineEvent): Timeline {
        if (findByOtid(event.otid) != null) return this
        val insertIdx = events.indexOfFirst { it.position > event.position }
        val newEvents = if (insertIdx == -1) events + event
                       else events.toMutableList().also { it.add(insertIdx, event) }
        return copy(events = newEvents)
    }

    /** Mark a Local event as [DeliveryState.SENT]. No-op for Confirmed events. */
    fun markSent(otid: String): Timeline = updateLocal(otid) { it.copy(deliveryState = DeliveryState.SENT) }

    /** Mark a Local event as [DeliveryState.FAILED]. No-op for Confirmed events. */
    fun markFailed(otid: String): Timeline = updateLocal(otid) { it.copy(deliveryState = DeliveryState.FAILED) }

    private inline fun updateLocal(otid: String, transform: (TimelineEvent.Local) -> TimelineEvent.Local): Timeline {
        val idx = events.indexOfFirst { it.otid == otid && it is TimelineEvent.Local }
        if (idx == -1) return this
        val local = events[idx] as TimelineEvent.Local
        return copy(events = events.toMutableList().also { it[idx] = transform(local) })
    }
}

/** Generate a new client-side otid for outgoing messages. */
fun newOtid(): String = "client-${UUID.randomUUID()}"

/** Current wall-clock instant — abstracted for test injection. */
internal fun now(): Instant = Instant.now()
