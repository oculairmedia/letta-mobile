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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presentation-only lifetime shared by Android and Desktop. Closing never retires the writer. */
class CanonicalTimelinePresentation private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val lease: CanonicalTimelineCoordinator.Presentation,
    parentScope: CoroutineScope,
    val missingTarget: String?,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val owner = lease.owner
    private val resident = MutableStateFlow<Map<TimelineMessageId, Long>>(emptyMap())

    private val detached = kotlinx.coroutines.CompletableDeferred<Unit>()
    init {
        job.invokeOnCompletion {
            CoroutineScope(parentScope.coroutineContext + NonCancellable).launch {
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

    // Durability alone is not presentation: retain live until the matching revision is resident.
    val live: StateFlow<List<ChatRenderItem>> = combine(
        owner.session.live, owner.session.pending, resident,
    ) { publication, pending, presented ->
        val events = publication?.unpresentedEvents(presented).orEmpty()
        val confirmedOtids = events.mapTo(mutableSetOf()) { it.otid }
        val optimistic = pending.filterNot { it.otid in confirmedOtids }
            .map { it.toRenderItem(owner.selection.scope.agentId) }
        val active = events.mapNotNull { event ->
            timelineEventToUiMessage(event, owner.selection.scope.agentId)?.let {
                val identity = publication?.settlementIdentities?.get(TimelineMessageId(event.serverId))?.value
                    ?: event.serverId
                ChatRenderItem.Single(
                    it, GroupPosition.None, keyOverride = "segment-$identity",
                )
            }
        }
        active.asReversed() + optimistic.asReversed()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Only actual resident rows count, never prefetched rows or a remembered revision watermark. */
    fun onResidentRows(rows: List<Row>) {
        if (!job.isActive) return
        val presented = rows.take(128).associate { it.identity to it.revision }
        resident.value = presented
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

    private fun project(record: TimelineSettledRecord): Row {
        val projection = record.projectBounded(owner.selection.scope, owner.selection.scope.agentId)
        val deferred = (projection as? TimelineSettledProjection.Deferred)?.reference
        val item = (projection as? TimelineSettledProjection.Rendered)?.item ?: ChatRenderItem.Single(
            UiMessage(record.key.identity.value, "assistant",
                if (deferred != null) "Content stored locally (${deferred.pointer.encodedBytes} bytes). Preview unavailable."
                else "This stored record cannot be displayed by this client version.",
                timestamp = ""),
            GroupPosition.None, keyOverride = "segment-${record.key.identity.value}",
        )
        return Row(record.key.identity, record.revision, item, deferred)
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
