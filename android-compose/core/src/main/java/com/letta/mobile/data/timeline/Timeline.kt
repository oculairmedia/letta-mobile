package com.letta.mobile.data.timeline

import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import java.time.Instant
import java.util.UUID

fun String.stripEnvelopeReminders(): String {
    return this.replace(Regex("<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL), "")
               .replace(Regex("<skill-reminder>.*?</skill-reminder>\\s*", RegexOption.DOT_MATCHES_ALL), "")
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

    /**
     * Origin of this event. Defaults to LETTA_SERVER for the strict-otid timeline
     * path. CLIENT_MODE_HARNESS marks events that came from / were sent through
     * the lettabot WS gateway, where strict otid reconcile isn't yet supported.
     * See [MessageSource] kdoc for details.
     */
    abstract val source: MessageSource

    /**
     * Optimistic, not yet confirmed by server. Historically only ever USER —
     * extended for letta-mobile-5s1n (Client Mode assistant streaming) so
     * in-flight assistant content + reasoning + tool calls can flow through
     * the timeline rather than the legacy in-memory `clientModeMessages` list.
     *
     * Strict-otid sends from Letta REST/SSE remain USER-only; the new fields
     * default to safe values and are unused for [MessageSource.LETTA_SERVER].
     * For [MessageSource.CLIENT_MODE_HARNESS] events that represent assistant
     * streaming, [messageType] is set accordingly and the structured fields
     * (toolCalls / approval / toolReturn / reasoningContent) carry the
     * stream payload until the SSE-side Confirmed event lands and the fuzzy
     * matcher (or future strict-otid path) collapses them.
     */
    data class Local(
        override val position: Double,
        override val otid: String,
        override val content: String,
        val role: Role = Role.USER,
        val sentAt: Instant,
        val deliveryState: DeliveryState,
        override val attachments: List<MessageContentPart.Image> = emptyList(),
        override val source: MessageSource = MessageSource.LETTA_SERVER,
        // letta-mobile-5s1n: assistant-streaming additive fields. All default
        // to USER-bubble-equivalent values so existing call sites compile
        // unchanged. Mirrors the corresponding fields on [Confirmed] so the
        // VM->UiMessage mapper can render Locals and Confirmeds uniformly.
        val messageType: TimelineMessageType = TimelineMessageType.USER,
        val toolCalls: List<com.letta.mobile.data.model.ToolCall> = emptyList(),
        val approvalRequestId: String? = null,
        val approvalDecided: Boolean = false,
        val toolReturnContent: String? = null,
        val toolReturnIsError: Boolean = false,
        val reasoningContent: String? = null,
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
        // Populated for TOOL_CALL events (ToolCallMessage + ApprovalRequestMessage).
        // letta-mobile-mge5.14: without these the UI dropped tool calls entirely
        // because the VM->UiMessage mapper couldn't reconstruct structured args.
        val toolCalls: List<com.letta.mobile.data.model.ToolCall> = emptyList(),
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
        override val source: MessageSource = MessageSource.LETTA_SERVER,
    ) : TimelineEvent()
}

enum class Role { USER, ASSISTANT, SYSTEM }

enum class DeliveryState { SENDING, SENT, FAILED }

/**
 * Default window for the Client Mode fuzzy reconcile path. See
 * [Timeline.collapseClientModeFuzzyMatch]. 10s is a balance between catching
 * realistic round-trips through the lettabot WS gateway → SDK → Letta server
 * → reconcile loop and minimising the chance of false positives from the user
 * legitimately sending the same content twice in quick succession.
 */
const val CLIENT_MODE_FUZZY_WINDOW_MS: Long = 10_000

/**
 * Result of a fuzzy-collapse attempt. [collapsed] is null when no match was
 * found — caller should fall through to standard `insertOrdered` semantics.
 */
data class FuzzyCollapseResult(
    val timeline: Timeline,
    val collapsed: FuzzyCollapseTrace?,
)

/**
 * Trace metadata for an executed fuzzy collapse. Callers MUST log this at INFO
 * level when present (Meridian guardrail (2) on letta-mobile-c87t). Used to
 * verify behavioural parity when 8cm8 lands and replaces this path with strict
 * otid reconcile, and for triaging "my message duplicated" reports.
 */
data class FuzzyCollapseTrace(
    val localOtid: String,
    val serverId: String,
    val deltaMs: Long,
    val contentPrefix: String,
    val source: MessageSource,
)

/**
 * Origin of a timeline event. Used internally for telemetry, audit, and to scope
 * source-specific reconcile behaviour (notably: the fuzzy duplicate-match path
 * that is gated to `CLIENT_MODE_HARNESS` while `letta-mobile-8pyt`/`-26pf`/`-8cm8`
 * land and enable strict otid-by-otid reconcile through the lettabot gateway).
 *
 * NOT surfaced in the UI rendering layer — see `letta-mobile-c87t` notes. If you
 * find yourself adding a Compose if-branch keyed off this field, stop and read
 * the issue first; the value of source-scoping is keeping it OUT of presentation.
 */
enum class MessageSource {
    /** Standard path: mobile → Letta REST/SSE. Reconciles by otid. */
    LETTA_SERVER,
    /**
     * Routed via the lettabot WS gateway → Letta Code SDK → Letta server. The
     * SDK does not currently accept a client-supplied otid (`letta-mobile-8pyt`),
     * so server-echoed messages won't carry our otid back. Reconciliation falls
     * back to a 10s+content fuzzy match, scoped to this source value only.
     */
    CLIENT_MODE_HARNESS,
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
                "uniqueOtids" to events.map { it.otid }.toSet().size,
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
     * Find an event by its server message id. Used by the resume-stream
     * subscriber to dedupe events that may arrive via both the stream and
     * a concurrent reconcile. See letta-mobile-mge5.
     */
    fun findByServerId(serverId: String): TimelineEvent.Confirmed? =
        events.firstOrNull { it is TimelineEvent.Confirmed && it.serverId == serverId } as? TimelineEvent.Confirmed


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
     * letta-mobile-c87t fuzzy reconcile path.
     *
     * When a Confirmed user message arrives whose otid does not match any
     * existing Local (the strict path), this method looks for a recently-appended
     * Client-Mode-source Local with identical content within [windowMillis] of
     * the Confirmed event's [TimelineEvent.Confirmed.date], and if found,
     * collapses the pair by deleting the Local and inserting the Confirmed at
     * the Local's position (so there's no visual jump).
     *
     * SCOPE: scoped strictly via [TimelineEvent.Local.source] == [MessageSource.CLIENT_MODE_HARNESS].
     * NOT keyed off any ambient flag (see Meridian guardrail (1) on
     * letta-mobile-c87t). Adding a new caller that wants to engage this path
     * MUST stamp `source = CLIENT_MODE_HARNESS` on the Local at construction
     * time, or this matcher will (correctly) ignore it.
     *
     * TODO(letta-mobile-8cm8): replace this fuzzy match with strict otid-by-otid
     * reconcile once 26pf (gateway forwards otid) and 8pyt (SDK accepts otid on
     * Session.send()) land. Worst-case false positive today: the same exact
     * text sent twice inside a [windowMillis] window under Client Mode collapses
     * to one bubble.
     *
     * Returns the (possibly modified) Timeline plus a metadata payload describing
     * the collapse for telemetry; callers should log every non-null result at
     * INFO level (Meridian guardrail (2)). Returns null in `collapsed` when no
     * match was found — callers fall through to `insertOrdered`.
     */
    fun collapseClientModeFuzzyMatch(
        confirmed: TimelineEvent.Confirmed,
        windowMillis: Long = CLIENT_MODE_FUZZY_WINDOW_MS,
    ): FuzzyCollapseResult {
        // In Client Mode, we must fuzzy-collapse both USER and agent-generated turns 
        // (Assistant, Reasoning, Tool Call) because they are dual-streamed locally and via SSE.
        if (confirmed.messageType != TimelineMessageType.USER && 
            confirmed.messageType != TimelineMessageType.ASSISTANT &&
            confirmed.messageType != TimelineMessageType.REASONING &&
            confirmed.messageType != TimelineMessageType.TOOL_CALL) {
            return FuzzyCollapseResult(this, null)
        }

        val candidate = events.asSequence()
            .filterIsInstance<TimelineEvent.Local>()
            .filter { it.source == MessageSource.CLIENT_MODE_HARNESS }
            .filter { it.role == confirmed.role }
            .filter { 
                if (confirmed.messageType == TimelineMessageType.USER) {
                    it.content.stripEnvelopeReminders() == confirmed.content.stripEnvelopeReminders()
                } else {
                    // For streaming agent messages, content lengths may differ mid-stream.
                    // We rely on role and time proximity to match the ongoing local stream.
                    true
                }
            }
            .filter {
                val deltaMs = kotlin.math.abs(
                    java.time.Duration.between(it.sentAt, confirmed.date).toMillis()
                )
                deltaMs <= windowMillis
            }
            // Prefer the most recent matching Local, in case the user sent the
            // same content multiple times within the window.
            .maxByOrNull { it.sentAt }
            ?: return FuzzyCollapseResult(this, null)

        val deltaMs = java.time.Duration.between(candidate.sentAt, confirmed.date).toMillis()
        val stabilized = confirmed.copy(
            position = candidate.position,
            otid = candidate.otid // Preserve local otid so upsertClientModeLocal stops updating
        )
        val newEvents = events.toMutableList().apply {
            removeAll { it.otid == candidate.otid }
            // Re-insert at correct position (which may now differ since we just
            // removed the Local). The Confirmed event's stabilized position
            // matches the Local's, so an ordered insert places it correctly.
            val insertIdx = indexOfFirst { it.position > stabilized.position }
            if (insertIdx == -1) add(stabilized) else add(insertIdx, stabilized)
        }
        return FuzzyCollapseResult(
            timeline = copy(events = newEvents),
            collapsed = FuzzyCollapseTrace(
                localOtid = candidate.otid,
                serverId = confirmed.serverId,
                deltaMs = deltaMs,
                contentPrefix = candidate.content.take(40),
                source = candidate.source,
            ),
        )
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
        val idx = events.indexOfFirst {
            it is TimelineEvent.Confirmed && it.serverId == confirmed.serverId
        }
        if (idx == -1) return this
        val existing = events[idx] as TimelineEvent.Confirmed
        val stabilized = confirmed.copy(
            position = existing.position,
            otid = existing.otid ?: confirmed.otid // Preserve the local otid so upsertClientModeLocal can stop updating
        )
        return copy(events = events.toMutableList().also { it[idx] = stabilized })
    }

    /**
     * letta-mobile-5s1n: insert-or-update a Client Mode assistant-streaming
     * Local. Used by [TimelineSyncLoop.upsertClientModeLocalAssistantChunk]
     * to thread incremental SSE-style chunks through the timeline.
     *
     * Contract: identifies the target Local by `otid`. If present, applies
     * [transform] preserving position. If absent, builds a new Local via
     * [build] and appends at end. Always returns a Timeline; never throws.
     *
     * Scoped to Locals with `source = CLIENT_MODE_HARNESS` — strict-otid
     * USER Locals on the LETTA_SERVER path remain untouched.
     */
    fun upsertClientModeLocal(
        otid: String,
        transform: (TimelineEvent.Local) -> TimelineEvent.Local,
        build: () -> TimelineEvent.Local,
    ): Timeline {
        val idx = events.indexOfFirst { it.otid == otid }
        if (idx >= 0) {
            val existingEvent = events[idx]
            if (existingEvent is TimelineEvent.Confirmed) {
                // The server has already confirmed this event and we collapsed it.
                // Drop the local update so we don't recreate it.
                return this
            }
            val existing = existingEvent as TimelineEvent.Local
            if (existing.source != MessageSource.CLIENT_MODE_HARNESS) {
                Telemetry.event(
                    "Timeline", "upsertClientModeLocal.wrongSource",
                    "conversationId" to conversationId,
                    "otid" to otid,
                    "source" to existing.source.name,
                    level = Telemetry.Level.WARN,
                )
                return this
            }
            val updated = transform(existing).copy(
                position = existing.position,
                otid = existing.otid,
                source = MessageSource.CLIENT_MODE_HARNESS,
            )
            return copy(events = events.toMutableList().also { it[idx] = updated })
        }
        val seed = build().copy(
            otid = otid,
            position = nextLocalPosition(),
            source = MessageSource.CLIENT_MODE_HARNESS,
        )
        return append(seed)
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
