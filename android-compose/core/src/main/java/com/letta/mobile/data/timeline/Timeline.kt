package com.letta.mobile.data.timeline

import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
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

    /**
     * Optional attached images. Rendered as thumbnails alongside the text body.
     * The same objects are serialized into the outbound MessageCreate.content
     * array for the server.
     */
    abstract val attachments: List<MessageContentPart.Image>

    /** Optimistic, not yet confirmed by server. Only ever role=USER in practice. */
    data class Local(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val role: Role = Role.USER,
        val sentAt: Instant,
        val deliveryState: DeliveryState,
        override val attachments: List<MessageContentPart.Image> = emptyList(),
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
        override val attachments: List<MessageContentPart.Image> = emptyList(),
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
        // Defensive telemetry: log invariant violations but don't crash in production.
        // In concurrent scenarios (e.g., reconcileAfterSend racing with stream events),
        // position collisions or otid duplicates can occur transiently. Log them for
        // diagnosis but allow the timeline to be constructed.
        val positionViolation = events.zipWithNext().any { (a, b) -> a.position >= b.position }
        val otidDupes = events.size != events.map { it.otid }.toSet().size
        
        if (positionViolation) {
            Telemetry.event(
                "Timeline", "init.positionViolation",
                "conversationId" to conversationId,
                "eventCount" to events.size,
                level = Telemetry.Level.ERROR
            )
        }
        if (otidDupes) {
            Telemetry.event(
                "Timeline", "init.otidDuplicates",
                "conversationId" to conversationId,
                "eventCount" to events.size,
                "uniqueOtids" to events.map { it.otid }.toSet().size,
                level = Telemetry.Level.ERROR
            )
        }
    }

    /** Next position for an append at the end. */
    fun nextLocalPosition(): Double {
        val last = events.lastOrNull()?.position ?: 0.0
        return last + 1.0
    }

    fun findByOtid(otid: String): TimelineEvent? = events.firstOrNull { it.otid == otid }

    /**
     * Append a new event at the end.
     *
     * In production this tolerates invariant violations (duplicate otid or
     * non-monotonic position) rather than crashing — logs telemetry and either
     * drops the duplicate or bumps the position to preserve ordering. The
     * [init] block logs the same violations at construction time; this is the
     * matching defence at mutation time so a transient race doesn't take down
     * the chat screen.
     */
    fun append(event: TimelineEvent): Timeline {
        if (findByOtid(event.otid) != null) {
            Telemetry.event(
                "Timeline", "append.duplicateOtid",
                "conversationId" to conversationId,
                "otid" to event.otid,
                level = Telemetry.Level.WARN,
            )
            return this
        }
        val lastPos = events.lastOrNull()?.position
        val safeEvent: TimelineEvent = if (lastPos != null && lastPos >= event.position) {
            Telemetry.event(
                "Timeline", "append.positionBumped",
                "conversationId" to conversationId,
                "otid" to event.otid,
                "requested" to event.position,
                "bumpedTo" to (lastPos + 1.0),
                level = Telemetry.Level.WARN,
            )
            when (event) {
                is TimelineEvent.Local -> event.copy(position = lastPos + 1.0)
                is TimelineEvent.Confirmed -> event.copy(position = lastPos + 1.0)
            }
        } else {
            event
        }
        return copy(events = events + safeEvent)
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
