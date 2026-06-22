package com.letta.mobile.data.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

fun String.stripEnvelopeReminders(): String {
    return this.replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>\\s*"), "")
               .replace(Regex("<skill-reminder>[\\s\\S]*?</skill-reminder>\\s*"), "")
               .trim()
}

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
@Immutable
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
    abstract val attachments: PersistentList<MessageContentPart.Image>

    abstract val source: MessageSource

    /**
     * Optimistic, not yet confirmed by server.
     */
    data class Local(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val role: Role = Role.USER,
        val sentAt: TimelineInstant,
        val deliveryState: DeliveryState,
        override val attachments: PersistentList<MessageContentPart.Image> = persistentListOf(),
        override val source: MessageSource = MessageSource.LETTA_SERVER,
        // letta-mobile-5s1n: assistant-streaming additive fields. All default
        // to USER-bubble-equivalent values so existing call sites compile
        // unchanged. Mirrors the corresponding fields on [Confirmed] so the
        // VM->UiMessage mapper can render Locals and Confirmeds uniformly.
        val messageType: TimelineMessageType = TimelineMessageType.USER,
        val toolCalls: PersistentList<ToolCall> = persistentListOf(),
        val approvalRequestId: String? = null,
        val approvalDecided: Boolean = false,
        val toolReturnContent: String? = null,
        val toolReturnIsError: Boolean = false,
        val toolReturnContentByCallId: PersistentMap<String, String> = persistentMapOf(),
        val toolReturnIsErrorByCallId: PersistentMap<String, Boolean> = persistentMapOf(),
        val toolStartedAtByCallId: PersistentMap<String, TimelineInstant> = persistentMapOf(),
        val toolCompletedAtByCallId: PersistentMap<String, TimelineInstant> = persistentMapOf(),
        val toolBatchIdByCallId: PersistentMap<String, String> = persistentMapOf(),
        val reasoningContent: String? = null,
    ) : TimelineEvent()

    /** Confirmed via server. */
    data class Confirmed(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val serverId: String,
        val messageType: TimelineMessageType,
        val date: TimelineInstant,
        val runId: String?,
        val stepId: String?,
        override val attachments: PersistentList<MessageContentPart.Image> = persistentListOf(),
        // Populated for TOOL_CALL events (ToolCallMessage + ApprovalRequestMessage).
        // letta-mobile-mge5.14: without these the UI dropped tool calls entirely
        // because the VM->UiMessage mapper couldn't reconstruct structured args.
        val toolCalls: PersistentList<ToolCall> = persistentListOf(),
        // Populated for ApprovalRequestMessage so the UI can surface an
        // approve/reject affordance tied to the original request.
        val approvalRequestId: String? = null,
        // True once an ApprovalResponseMessage for this request has been seen
        // (either from the stream, the send echo, or reconcile). The UI hides
        // the approve/reject buttons once decided. letta-mobile-mge5.15: before
        // this, every tool call still showed approve/reject buttons even after
        // the server had already auto-approved them.
        val approvalDecided: Boolean = false,
        // Attached ToolReturnMessage output body, if one has been observed for
        // any of this event's tool calls. letta-mobile-mge5.19: previously
        // tool_return_messages rendered as their own separate bubble, making
        // the command/output relationship hard to read. Now attached directly
        // to the invoking TOOL_CALL event so the UI can show command +
        // collapsible output in a single card.
        val toolReturnContent: String? = null,
        val toolReturnIsError: Boolean = false,
        val toolReturnContentByCallId: PersistentMap<String, String> = persistentMapOf(),
        val toolReturnIsErrorByCallId: PersistentMap<String, Boolean> = persistentMapOf(),
        override val source: MessageSource = MessageSource.LETTA_SERVER,
        val seqId: Int? = null,
    ) : TimelineEvent()
}

enum class Role { USER, ASSISTANT, SYSTEM }

enum class DeliveryState { SENDING, SENT, FAILED }

enum class MessageSource {
    LETTA_SERVER,
}

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
    /**
     * Server-emitted error frame. Rendered as a system-style bubble with
     * destructive accent so the user sees that the run aborted instead of
     * a silent dropped spinner. letta-mobile-5s1n.
     */
    ERROR,
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
@Immutable
data class Timeline(
    val conversationId: String,
    val events: PersistentList<TimelineEvent> = persistentListOf(),
    /** Highest seen server message id. Advances as stream/reconcile events arrive. */
    val liveCursor: String? = null,
    /** Cursor for backfill pagination (oldest known message id). */
    val backfillCursor: String? = null,
    /** Monotonic fingerprint for all non-tail events so UI projections can trust unchanged history. */
    val stablePrefixVersion: Long = events.stablePrefixFingerprint(),
) {
    private val otidToIndex: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<String, Int>(events.size).also { map ->
            events.forEachIndexed { i, e -> map[e.otid] = i }
        }
    }

    private val serverIdToIndex: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<String, Int>(events.size).also { map ->
            events.forEachIndexed { i, e ->
                if (e is TimelineEvent.Confirmed) map[e.serverId] = i
            }
        }
    }

    private val identityKeySet: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashSet<String>(events.size * 3).also { keys ->
            events.forEach { event -> keys += event.identityKeys() }
        }
    }

    init {
        val positionViolation = events.zipWithNext().any { (a, b) -> a.position >= b.position }
        val otidDupes = events.size != events.distinctBy { it.otid }.size

        if (positionViolation) {
            Telemetry.error(
                "Timeline", "init.positionViolation",
                IllegalStateException("Timeline events are not strictly ordered by position"),
                "conversationId" to conversationId,
                "eventCount" to events.size,
            )
        }
        if (otidDupes) {
            Telemetry.error(
                "Timeline", "init.otidDuplicates",
                IllegalStateException("Timeline contains duplicate otids"),
                "conversationId" to conversationId,
                "eventCount" to events.size,
                "uniqueOtids" to otidToIndex.size,
            )
        }
    }

    /** Next position for an append at the end. */
    fun nextLocalPosition(): Double {
        val last = events.lastOrNull()?.position ?: 0.0
        return last + 1.0
    }

    fun findByOtid(otid: String): TimelineEvent? = otidToIndex[otid]?.let { events[it] }

    fun containsIdentityFor(event: TimelineEvent): Boolean {
        val tail = events.lastOrNull()
        if (tail != null && tail.identityKeys().any { it in event.identityKeys() }) return true
        return event.identityKeys().any { it in identityKeySet }
    }

    /**
     * Find an event by its server message id. Used by the resume-stream
     * subscriber to dedupe events that may arrive via both the stream and
     * a concurrent reconcile. See letta-mobile-mge5.
     */
    fun findByServerId(
        serverId: String,
        messageType: TimelineMessageType? = null,
    ): TimelineEvent.Confirmed? {
        val tail = events.lastOrNull()
        if (tail is TimelineEvent.Confirmed && tail.serverId == serverId) {
            if (messageType != null && tail.messageType != messageType) return null
            return tail
        }

        val idx = serverIdToIndex[serverId] ?: return null
        val event = events[idx] as? TimelineEvent.Confirmed ?: return null
        if (messageType != null && event.messageType != messageType) return null
        return event
    }


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
        return copy(events = events.adding(safeEvent), stablePrefixVersion = stablePrefixVersion + 1)
    }

    /**
     * Replace a Local event (identified by otid) with a Confirmed event.
     * Preserves the Local's position to avoid visual jumps in the UI.
     *
     * If no Local with that otid is found (e.g., backfill case), inserts
     * the Confirmed event at its natural position instead.
     */
    fun replaceLocal(otid: String, confirmed: TimelineEvent.Confirmed): Timeline {
        val idx = otidToIndex[otid]?.takeIf { events[it] is TimelineEvent.Local } ?: return insertOrdered(confirmed)
        val local = events[idx]
        val stabilized = confirmed.copy(
            position = local.position,
            otid = local.otid,
            // The Letta server doesn't echo inbound image content back in the
            // confirmed message, so carry the locally-sent attachments forward;
            // otherwise the image the user just sent vanishes on confirmation.
            attachments = if (confirmed.attachments.isEmpty()) local.attachments else confirmed.attachments,
        )
        val newEvents = events.replacingAt(idx, stabilized)
        return copy(events = newEvents, stablePrefixVersion = stablePrefixVersion + 1)
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
        val newEvents = if (insertIdx == -1) events.adding(event)
                       else events.addingAt(insertIdx, event)
        return copy(events = newEvents, stablePrefixVersion = stablePrefixVersion + 1)
    }

    /**
     * Replace an existing Confirmed event with an updated one that carries the
     * same server id. Used by the resume-stream subscriber to apply token
     * deltas — each delta frame carries the message's server id but has a
     * growing `content`/`arguments` payload. Without in-place replacement,
     * deltas would be dropped by server-id dedupe and the UI would appear
     * choppy — it would only update once per terminal envelope.
     *
     * Preserves the original event's [position] so visual ordering doesn't
     * jitter across deltas. Returns this timeline unchanged if no event with
     * the given serverId exists (caller should append instead).
     *
     * Added for letta-mobile-mge5.
     */
    fun replaceByServerId(confirmed: TimelineEvent.Confirmed): Timeline {
        val tailIdx = events.lastIndex
        val tail = events.lastOrNull()
        if (tail is TimelineEvent.Confirmed && tail.serverId == confirmed.serverId) {
            if (tail.messageType != confirmed.messageType) return this
            val stabilized = confirmed.copy(
                position = tail.position,
                otid = tail.otid
            )
            return copy(events = events.replacingAt(tailIdx, stabilized))
        }

        val idx = serverIdToIndex[confirmed.serverId] ?: return this
        val existing = events[idx]
        if (existing !is TimelineEvent.Confirmed || existing.messageType != confirmed.messageType) return this
        val stabilized = confirmed.copy(
            position = existing.position,
            otid = existing.otid
        )
        val replaced = events.replacingAt(idx, stabilized)
        if (!events.hasDuplicateOtidOutside(index = idx, otid = stabilized.otid)) {
            return copy(events = replaced, stablePrefixVersion = stablePrefixVersion + 1)
        }

        val deduped = replaced.filterIndexed { eventIndex, event ->
            eventIndex == idx || event.otid != stabilized.otid
        }
        Telemetry.event(
            "Timeline", "replaceByServerId.duplicateOtidDropped",
            "conversationId" to conversationId,
            "serverId" to confirmed.serverId,
            "otid" to stabilized.otid,
            level = Telemetry.Level.WARN,
        )
        return copy(events = deduped.toPersistentList(), stablePrefixVersion = stablePrefixVersion + 1)
    }

    private fun List<TimelineEvent>.hasDuplicateOtidOutside(index: Int, otid: String): Boolean {
        for (i in indices) {
            if (i != index && this[i].otid == otid) return true
        }
        return false
    }

    /** Mark a Local event as [DeliveryState.SENT]. No-op for Confirmed events. */
    fun markSent(otid: String): Timeline = updateLocal(otid) { it.copy(deliveryState = DeliveryState.SENT) }

    /** Mark a Local event as [DeliveryState.FAILED]. No-op for Confirmed events. */
    fun markFailed(otid: String): Timeline = updateLocal(otid) { it.copy(deliveryState = DeliveryState.FAILED) }

    private inline fun updateLocal(otid: String, transform: (TimelineEvent.Local) -> TimelineEvent.Local): Timeline {
        val idx = otidToIndex[otid]?.takeIf { events[it] is TimelineEvent.Local } ?: return this
        val local = events[idx] as TimelineEvent.Local
        return copy(events = events.replacingAt(idx, transform(local)), stablePrefixVersion = stablePrefixVersion + 1)
    }
}

private fun List<TimelineEvent>.stablePrefixFingerprint(): Long {
    var fingerprint = 1125899906842597L
    for (index in 0 until lastIndex) {
        fingerprint = fingerprint * 31 + this[index].hashCode()
    }
    return fingerprint
}

/** Generate a new client-side otid for outgoing messages. */
fun newOtid(): String = "client-${newTimelineClientId()}"

/** Current wall-clock instant — abstracted for test injection. */
internal fun now(): TimelineInstant = timelineNow()
