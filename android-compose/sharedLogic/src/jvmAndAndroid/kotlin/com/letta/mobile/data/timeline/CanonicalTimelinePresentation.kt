package com.letta.mobile.data.timeline

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.filter
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.plus

/** Presentation-only lifetime shared by Android and Desktop. Closing never retires the writer. */
class CanonicalTimelinePresentation private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val lease: CanonicalTimelineCoordinator.Presentation,
    parentScope: CoroutineScope,
    val missingTarget: String?,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = parentScope + job
    private val owner = lease.owner
    private val resident = MutableStateFlow<Map<TimelineMessageId, Long>>(emptyMap())
    private val residentOtids = MutableStateFlow<Set<String>>(emptySet())

    private val detached = kotlinx.coroutines.CompletableDeferred<Unit>()
    init {
        job.invokeOnCompletion {
            (parentScope + NonCancellable).launch {
                try {
                    coordinator.detach(lease)
                    detached.complete(Unit)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    // Retiring the viewport is not a detach failure; close() awaits this.
                    detached.completeExceptionally(cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    detached.completeExceptionally(failure)
                }
            }
        }
    }

    data class Row(
        val identity: TimelineMessageId,
        val revision: Long,
        val item: ChatRenderItem,
        val deferred: TimelineBodyReference? = null,
        // Identities differ between the streamed and stored copy of a send; the otid does not.
        val otid: String = "",
    )

    private val anchor = MutableStateFlow(lease.anchor)
    val target = MutableStateFlow(lease.anchor?.identity?.value)
    var viewport: Pair<String, Int>? = null

    suspend fun navigate(identity: String?): Boolean {
        check(job.isActive)
        val lookup = coordinator.attach(owner, identity?.let(::TimelineMessageId)) ?: return false
        try {
            viewport = null
            target.value = identity
            anchor.value = lookup.anchor
            resident.value = emptyMap()
        } finally {
            withContext(NonCancellable) { coordinator.detach(lookup) }
        }
        return true
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settled: Flow<PagingData<Row>> = anchor.flatMapLatest { key ->
        owner.session.paging(owner.selection, key).map { page ->
            page.filter { record ->
                if (record.isPreview || record.contentType != "application/vnd.letta.timeline-event+json;version=1") true
                else {
                    val event = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.json.decodeFromString(
                        com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), record.body.decodeToString(),
                    ).toConfirmedTimelineEvent()
                    timelineEventToUiMessage(event, owner.selection.scope.agentId) != null &&
                        !owner.session.engine.isSuppressed(owner.selection, record.key.identity, record.revision, event)
                }
            }.map { record -> project(record) }
        }
    }.cachedIn(scope)

    // Bounded by pending storage: pruned to the records still awaiting their durable echo.
    private val echoedOtids = mutableSetOf<String>()

    private val mutableLive = MutableStateFlow<List<ChatRenderItem>>(emptyList())

    /**
     * Published from this presentation's own scope and cancelled with it. Sharing eagerly through
     * stateIn would tie the projection's lifetime to whatever scope was handed in, which is the
     * caller's to cancel, not this presentation's.
     */
    val live: StateFlow<List<ChatRenderItem>> = mutableLive.asStateFlow()

    // Durability alone is not presentation: retain live until the settled ledger is at the turn's revision.
    private val liveProjection: Flow<List<ChatRenderItem>> = combine(
        owner.session.live, owner.session.pending, resident, residentOtids,
    ) { publication, pending, presented, settledOtids ->
        // A send is on screen once. The overlay and the optimistic bubble both stand down as soon
        // as the settled page carries that otid, which is the only identifier the streamed copy and
        // the stored copy share: their server ids and render keys never match.
        val events = publication?.overlayEvents(presented).orEmpty()
            .filterNot { it.otid.isNotBlank() && it.otid in settledOtids }
        // Only the sync path's durable echo clears pending storage, and the publication is dropped
        // the moment settlement is acknowledged. Remember the otids this turn echoed so the local
        // bubble cannot reappear in the gap between the overlay draining and that write landing.
        publication?.block?.events?.forEach { if (it.otid.isNotBlank()) echoedOtids += it.otid }
        echoedOtids.retainAll(pending.mapTo(mutableSetOf()) { it.otid })
        val optimistic = pending.filterNot { it.otid in echoedOtids || it.otid in settledOtids }
            .map { it.toRenderItem(owner.selection.scope.agentId) }
        val active = events.mapNotNull { event ->
            timelineEventToUiMessage(event, owner.selection.scope.agentId)?.let {
                ChatRenderItem.Single(it, GroupPosition.None, keyOverride = "segment-${event.serverId}")
            }
        }
        active.asReversed() + optimistic.asReversed()
    }

    init {
        scope.launch { liveProjection.collect { mutableLive.value = it } }
    }

    /** Only actual resident rows count, never prefetched rows or a remembered revision watermark. */
    fun onResidentRows(rows: List<Row>) {
        if (!job.isActive) return
        val presented = rows.take(128).associate { it.identity to it.revision }
        resident.value = presented
        residentOtids.value = rows.take(128).mapNotNullTo(mutableSetOf()) { it.otid.takeIf(String::isNotBlank) }
        val fence = owner.session.live.value?.fence ?: return
        scope.launch { coordinator.acknowledgeSettlement(owner, fence, presented) }
    }

    suspend fun readChunk(row: Row, offset: Long, maxBytes: Int): TimelineBodyChunk {
        check(job.isActive) { "Presentation closed" }
        val reference = requireNotNull(row.deferred) { "Row has no deferred body" }
        return owner.session.engine.resolveBodyChunk(owner.selection, reference, offset, maxBytes)
    }

    suspend fun readTextWindow(
        row: Row,
        field: TimelineSemanticField,
        scalarOffset: Long,
        decodeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): TimelineSemanticWindowResult = withContext(decodeDispatcher) {
        check(job.isActive) { "Presentation closed" }
        val result = TimelineSemanticBodyWindow.read(
            reference = requireNotNull(row.deferred), field = field, scalarOffset = scalarOffset,
            budget = TimelineSemanticBudget(maxInputBytes = 2L * 1024 * 1024),
        ) { offset, size -> readChunk(row, offset, size) }
        check(job.isActive) { "Presentation closed" }
        result
    }

    suspend fun close() {
        scope.cancel()
        withContext(NonCancellable) { detached.await() }
    }

    /**
     * Everything reaching this function came off the durable ledger, so any tool call in it has
     * already finished. Mark them, because the row carries no result of its own - the return is a
     * separate row - and the projection would otherwise read that absence as "still running".
     */
    private fun ChatRenderItem.settledToolCalls(): ChatRenderItem = when (this) {
        is ChatRenderItem.Single -> copy(message = message.withSettledToolCalls())
        is ChatRenderItem.RunBlock -> copy(messages = messages.map { it.first.withSettledToolCalls() to it.second })
        else -> this
    }

    private fun UiMessage.withSettledToolCalls(): UiMessage {
        val calls = toolCalls ?: return this
        if (calls.isEmpty() || calls.all { it.settled }) return this
        return copy(toolCalls = calls.map { if (it.settled) it else it.copy(settled = true) })
    }

    private fun project(record: TimelineSettledRecord): Row {
        val projection = record.projectBounded(owner.selection.scope, owner.selection.scope.agentId)
        val deferred = (projection as? TimelineSettledProjection.Deferred)?.reference
        val item = (projection as? TimelineSettledProjection.Rendered)?.item?.settledToolCalls() ?: ChatRenderItem.Single(
            UiMessage(record.key.identity.value, "assistant",
                if (deferred != null) "Content stored locally (${deferred.pointer.encodedBytes} bytes). Preview unavailable."
                else "This stored record cannot be displayed by this client version.",
                timestamp = ""),
            GroupPosition.None, keyOverride = "segment-${record.key.identity.value}",
        )
        // A deferred body is not decoded here, so its otid stays blank and simply never matches.
        val otid = if (deferred != null || record.isPreview ||
            record.contentType != "application/vnd.letta.timeline-event+json;version=1"
        ) "" else runCatching {
            com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.json.decodeFromString(
                com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), record.body.decodeToString(),
            ).toConfirmedTimelineEvent().otid
        }.getOrDefault("")
        return Row(record.key.identity, record.revision, item, deferred, otid)
    }

    companion object {
        /** Missing search targets fall back to a usable tail without replacing ingestion generation. */
        suspend fun open(
            coordinator: CanonicalTimelineCoordinator,
            owner: CanonicalTimelineCoordinator.Owner,
            parentScope: CoroutineScope,
            target: TimelineMessageId? = null,
        ): CanonicalTimelinePresentation {
            val requested = coordinator.attach(owner, target)
            val lease = requested ?: checkNotNull(coordinator.attach(owner))
            return try {
                CanonicalTimelinePresentation(coordinator, lease, parentScope, if (requested == null) target?.value else null)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { coordinator.detach(lease) }
                throw failure
            }
        }
    }
}
