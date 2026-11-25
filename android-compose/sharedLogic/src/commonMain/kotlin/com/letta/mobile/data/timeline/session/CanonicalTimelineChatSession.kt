package com.letta.mobile.data.timeline.session

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEnginePageOutcome
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineLivePublication
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.newOtid
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.toPendingTimelineEvent
import com.letta.mobile.ui.chat.session.TimelineChatSession
import com.letta.mobile.ui.chat.session.TimelineChatSessionPort
import com.letta.mobile.ui.chat.session.TimelineSend
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/**
 * The outbound transport behind one canonical prompt. The session mints the prompt's client identity
 * (its otid) and writes its optimistic row; only the host's own transport can deliver the message,
 * so the binding hands this seam one prompt at a time. The seam owns dispatch (it may queue or
 * stream) and must throw on a delivery failure so the session can hand that failure back to the
 * writer.
 */
fun interface CanonicalTimelineSend {
    /**
     * Delivers [message] as the host transport sees fit, under the identity the session minted and
     * already wrote optimistically.
     */
    suspend fun send(otid: String, message: TimelineSend)
}

/**
 * The canonical presentation of one conversation, drawn as a [TimelineChatSession] for the shared
 * chat owner ([TimelineChatSessionPort]): the same rows the paged canonical presentation
 * (com.letta.mobile.data.timeline.CanonicalTimelinePresentation) renders — the settled tail page,
 * the live overlay of an unfinished turn, and the prompts still being sent — read from the same
 * [CanonicalTimelineCoordinator.Owner] the presentation reads, with the conversation's only writers
 * left exactly where they already are (the engine, the coordinator and the pending store behind
 * [writer]).
 *
 * This session never reduces or projects on its own: the settled tail is the engine's own bounded
 * page read, the live rows are the live reducer's own publications, and the optimistic rows are the
 * pending store's own records. It only arranges those raw events into a [Timeline] value, so the two
 * surfaces of one conversation hold the same rows and neither can drift. Likewise it never writes:
 * [hydrate] delegates to the canonical recent-tail load, and [send] delegates to the captured
 * writer's pending-send family, exactly one optimistic row per send.
 *
 * One session, one conversation: like the presentation, a host that switches conversations builds a
 * new one and closes this one; [close] cancels this session's projection but never retires the
 * conversation's writer, which stays with its owner.
 */
class CanonicalTimelineChatSession(
    private val coordinator: CanonicalTimelineCoordinator,
    private val owner: CanonicalTimelineCoordinator.Owner,
    private val writer: TimelineExternalTransportWriter,
    private val transport: CanonicalTimelineSend,
    scope: CoroutineScope,
) : TimelineChatSession {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = scope + job
    private val conversationScope: TimelineScope = owner.selection.scope
    private val conversationId: String = conversationScope.conversationId

    private val mutableEvents = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = RELAY_BUFFER_CAPACITY)
    private val mintedOtid: AtomicRef<String?> = atomic(null)

    /**
     * The load and send outcomes the chat owner's contract observes: a hydrated tail, and a send the
     * transport refused. Transport-level frames stay on the host's own subscription; the session
     * relays only what it drove itself.
     */
    override val events: SharedFlow<TimelineSyncEvent> = mutableEvents.asSharedFlow()

    /**
     * The conversation as raw events: the settled tail page (one bounded page, exactly the read the
     * paged presentation pages through), the live overlay's events that no durable row carries yet,
     * and the prompts still awaiting their echo. Re-reads follow the engine's durable revision; live
     * and pending rows follow their own publications, one writer below either surface.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val timeline: StateFlow<Timeline> = combine(
        owner.session.publication
            .map { it.durableRevision }
            .distinctUntilChanged()
            .flatMapLatest { flow { emit(readSettledTail()) } },
        owner.session.live,
        owner.session.settling,
        owner.session.pending,
    ) { tail, live, settling, pending ->
        assembleTimeline(tail, settling + listOfNotNull(live), pending)
    }.stateIn(sessionScope, SharingStarted.WhileSubscribed(SESSION_STOP_TIMEOUT_MILLIS), Timeline(conversationId = conversationId))

    /**
     * The canonical open's first-page load: one recent-tail read through the coordinator (a
     * conversation read through the host feed is loaded by the host and reconciles nothing). The
     * conversation's runtime is already open in [owner]; hydrating never reopens it, and the load
     * itself is idempotent — a second hydrate merges a page the durable ledger already holds.
     */
    override suspend fun hydrate() {
        check(job.isActive) { "Session closed" }
        val result = coordinator.reconcileRecentDetailed(owner)
        if (result.outcome == TimelineEnginePageOutcome.Applied) {
            mutableEvents.emit(TimelineSyncEvent.Hydrated(result.appended))
        }
    }

    /**
     * Writes exactly one optimistic prompt row through the captured writer's pending-send family
     * (one identity, minted here, reused across the surface, the transport and later a retry), then
     * hands the message to the host transport under that identity. A refused delivery is reported
     * through the same family (the row is marked failed) and relayed on [events] — never a second
     * send path.
     */
    override suspend fun send(text: String, attachments: List<MessageContentPart.Image>) {
        check(job.isActive) { "Session closed" }
        val otid = newOtid()
        mintedOtid.value = otid
        writer.appendExternalTransportLocal(
            agentId = conversationScope.agentId,
            conversationId = conversationId,
            content = text,
            otid = otid,
            attachments = attachments,
        )
        try {
            transport.send(otid, TimelineSend(text, attachments))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            writer.markExternalTransportLocalFailed(
                agentId = conversationScope.agentId,
                conversationId = conversationId,
                otid = otid,
            )
            mutableEvents.emit(
                TimelineSyncEvent.StreamError(SEND_ERROR_TYPE, expectedFailure.message ?: SEND_ERROR_MESSAGE),
            )
        }
    }

    /**
     * The last identity this session minted for a send; a host derives its voice-send identity from
     * it rather than from a send path of its own.
     */
    internal val lastMintedOtid: String? get() = mintedOtid.value

    /**
     * Cancels this session's projection and its own coroutines, and waits until they stopped. The
     * conversation's writer, owner and transport are not this session's to retire: the host that
     * created them still holds them.
     */
    suspend fun close() {
        job.cancel()
        job.join()
    }

    /**
     * The settled tail as raw events: the engine's own bounded tail page read, decided with the same
     * hidden-row rules the presentation's paging rows use, so the session never shows a row the
     * presentation drops. Row identity keys, otids and server ids feed the drain of the live overlay.
     */
    private suspend fun readSettledTail(): SettledTail {
        val page = owner.session.engine.preparePage(
            selection = owner.selection,
            position = TimelineReadPosition.Tail,
            maxRows = owner.session.engine.budget.maxMetadataRows,
            ownAgentId = conversationScope.agentId,
        )
        // A row the page hides (opaque protocol body, hidden instructions, a suppressed fragment)
        // is hidden for the session too; the packets below it carry the raw events of what it draws.
        val shown = page.projectionInput.records.mapNotNull { row -> row.event?.takeIf { !row.excluded } }
        return SettledTail(
            events = shown,
            rowIdentities = page.projectionInput.records.mapTo(mutableSetOf()) { it.record.key.identity },
            rowOtids = shown.mapTo(mutableSetOf()) { it.otid },
            rowServerIds = shown.mapTo(mutableSetOf()) { it.serverId },
        )
    }

    /**
     * The engine's settled tail, live publications and pending prompts as one [Timeline] value.
     * Drain rules mirror what the presentation holds: a live event whose durable row has arrived
     * disappears from the overlay, and a prompt says goodbye once its echo — streamed or durable —
     * carries its identity. Positions are assigned here; every source only decides identity and
     * order.
     */
    private fun assembleTimeline(
        tail: SettledTail,
        publications: List<TimelineLivePublication>,
        pending: List<CanonicalPendingLocalStore.Record>,
    ): Timeline {
        val live = liveOverlay(tail, publications)
        val prompts = pending.filterNot { it.otid in tail.rowOtids || it.otid in liveOtids(live) }
        val events = ArrayList<TimelineEvent>(tail.events.size + prompts.size + live.size)
        events += tail.events
        events += prompts.map { it.toPendingTimelineEvent() }
        events += live
        return Timeline(conversationId = conversationId, events = events.positioned())
    }

    /** The publications' events no durable row carries yet, in turn order (settled turns, then live). */
    private fun liveOverlay(tail: SettledTail, publications: List<TimelineLivePublication>) =
        publications.flatMap { publication ->
            publication.block.events.filterNot { event ->
                (event.otid.isNotBlank() && event.otid in tail.rowOtids) ||
                    event.serverId in tail.rowServerIds ||
                    publication.aliases[event.serverId] in tail.rowIdentities
            }
        }

    /** Live events drain a pending prompt the host already echoed on the stream. */
    private fun liveOtids(live: List<TimelineEvent.Confirmed>): Set<String> = live.mapTo(mutableSetOf()) { it.otid }

    /** Events with strictly increasing positions, keeping each event's identity and content as-is. */
    private fun List<TimelineEvent>.positioned() =
        mapIndexedTo(ArrayList(size)) { index, event -> event.repositioned(index.toDouble()) }.toPersistentList()

    private fun TimelineEvent.repositioned(position: Double): TimelineEvent = when (this) {
        is TimelineEvent.Local -> copy(position = position)
        is TimelineEvent.Confirmed -> copy(position = position)
    }

    /** One read of the settled tail, with what drains the live overlay against it. */
    private data class SettledTail(
        val events: List<TimelineEvent.Confirmed>,
        val rowIdentities: Set<TimelineMessageId>,
        val rowOtids: Set<String>,
        val rowServerIds: Set<String>,
    )
}

/**
 * How long the projection keeps running after the last reader left, matching the chat owner's page
 * flows: leaving the chat and coming right back never re-reads the tail.
 */
private const val SESSION_STOP_TIMEOUT_MILLIS = 5_000L

/** Emits never suspend: hydrate and send relay their outcomes with buffer to spare. */
private const val RELAY_BUFFER_CAPACITY = 16

private const val SEND_ERROR_TYPE = "send"
private const val SEND_ERROR_MESSAGE = "Could not send the message"
