package com.letta.mobile.data.timeline

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.filter
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import java.util.concurrent.ConcurrentHashMap
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
    private val settledProjectionAdapter: TimelineSettledProjectionAdapter,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = parentScope + job
    private val owner = lease.owner
    private val resident = MutableStateFlow<Map<TimelineMessageId, Long>>(emptyMap())
    private val residentOtids = MutableStateFlow<Set<String>>(emptySet())
    private val residentServerIds = MutableStateFlow<Set<String>>(emptySet())
    private val streamedKeyAliases = ConcurrentHashMap<TimelineMessageId, String>()

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
        // Storage identity may be remapped; server identity still matches the live event.
        val serverId: String = "",
        // A grouped run owns several durable records; all must count as resident for settlement.
        val residentEvents: List<TimelineResidentEvent> = emptyList(),
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
        owner.session.paging(owner.selection, key, settledProjectionAdapter).map { page ->
            page.filter { it.preparedPresentation !is TimelineSettledPresentation.Drop }
                .map { record -> project(record, requireNotNull(record.preparedPresentation)) }
        }
    }.cachedIn(scope)

    // Bounded by pending storage: pruned to the records still awaiting their durable echo. Each
    // otid remembers the turn whose overlay echoed it.
    private val echoedOtids = mutableMapOf<String, TimelineLiveFence>()

    private val mutableLive = MutableStateFlow<List<ChatRenderItem>>(emptyList())

    /**
     * Published from this presentation's own scope and cancelled with it. Sharing eagerly through
     * stateIn would tie the projection's lifetime to whatever scope was handed in, which is the
     * caller's to cancel, not this presentation's.
     */
    val live: StateFlow<List<ChatRenderItem>> = mutableLive.asStateFlow()

    // Durability alone is not presentation: retain live until the settled ledger is at the turn's revision.
    private val liveProjection: Flow<List<ChatRenderItem>> = combine(
        owner.session.live, owner.session.pending, resident, residentOtids, residentServerIds,
    ) { publication, pending, presented, settledOtids, settledServerIds ->
        cacheStreamedAliases(publication)
        // One logical event stays on screen once. Sends converge by otid; server-originated
        // reasoning and assistant frames converge by server id even when storage remaps the row key.
        val events = publication?.overlayEvents(presented).orEmpty()
            .filterNot { event ->
                (event.otid.isNotBlank() && event.otid in settledOtids) ||
                    event.serverId in settledServerIds
            }
        // Only the sync path's durable echo clears pending storage, and the publication is dropped
        // the moment settlement is acknowledged. Remember the otids this turn echoed so the local
        // bubble cannot reappear in the gap between the overlay draining and that write landing.
        //
        // An otid is remembered only while the turn that echoed it is still the resident one. Once
        // a different turn's overlay replaces it with the echo still not durable (its repair never
        // committed), the overlay's copy of the prompt is gone; hiding the local bubble as well
        // made the user's prompt vanish from the timeline.
        publication?.let { current ->
            echoedOtids.values.removeAll { it !== current.fence }
            current.block.events.forEach { if (it.otid.isNotBlank()) echoedOtids[it.otid] = current.fence }
        }
        echoedOtids.keys.retainAll(pending.mapTo(mutableSetOf()) { it.otid })
        val optimistic = pending.filterNot { it.otid in echoedOtids || it.otid in settledOtids }
            .map { it.toRenderItem(owner.selection.scope.agentId) }
        val activeMessages = events.mapNotNull { event ->
            timelineEventToUiMessage(event, owner.selection.scope.agentId)
        }
        val active = buildChatRenderModel(
            messages = activeMessages,
            mode = ChatDisplayMode.Interactive,
            activeAgentId = owner.selection.scope.agentId,
        ).renderItems
        active + optimistic.asReversed()
    }

    private fun cacheStreamedAliases(publication: TimelineLivePublication?) {
        val aliases = publication?.aliases ?: return
        if (aliases.isEmpty()) return
        for ((serverId, canonical) in aliases) {
            if (publication.block.events.any { it.serverId == serverId }) {
                streamedKeyAliases.putIfAbsent(canonical, "segment-$serverId")
            }
        }
    }

    init {
        scope.launch { liveProjection.collect { mutableLive.value = it } }
    }

    /** Only actual resident rows count, never prefetched rows or a remembered revision watermark. */
    fun onResidentRows(rows: List<Row>) {
        if (!job.isActive) return
        val residents = rows.take(128).flatMap { row ->
            row.residentEvents.ifEmpty {
                listOf(TimelineResidentEvent(row.identity, row.revision, row.otid, row.serverId))
            }
        }
        val presented = residents.associate { it.identity to it.revision }
        resident.value = presented
        residentOtids.value = residents.mapNotNullTo(mutableSetOf()) { it.otid.takeIf(String::isNotBlank) }
        residentServerIds.value = residents.mapNotNullTo(mutableSetOf()) { it.serverId.takeIf(String::isNotBlank) }
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

    private fun ChatRenderItem.withKeyOverride(key: String): ChatRenderItem = when (this) {
        is ChatRenderItem.Single -> copy(keyOverride = key)
        // A run block's existing key is shared by live and hydrated projection.
        is ChatRenderItem.RunBlock -> this
        else -> this
    }

    /** Preserve the LazyColumn slot while an aliased streamed row becomes its canonical ledger row. */
    private fun replacementKey(identity: TimelineMessageId): String {
        streamedKeyAliases[identity]?.let { return it }
        val live = owner.session.live.value
        val streamedId = live?.aliases?.entries?.singleOrNull { (serverId, canonical) ->
            canonical == identity && live.block.events.any { it.serverId == serverId }
        }?.key
        val key = "segment-${streamedId ?: identity.value}"
        if (streamedId != null) {
            streamedKeyAliases[identity] = key
        }
        return key
    }

    private fun project(record: TimelineSettledRecord, presentation: TimelineSettledPresentation): Row = when (presentation) {
        is TimelineSettledPresentation.Render -> Row(
            record.key.identity,
            record.revision,
            presentation.item.settledToolCalls().withKeyOverride(replacementKey(record.key.identity)),
            otid = presentation.event.otid,
            serverId = presentation.event.serverId,
            residentEvents = presentation.residentEvents.ifEmpty {
                listOf(TimelineResidentEvent(record.key.identity, record.revision, presentation.event.otid, presentation.event.serverId))
            },
        )
        is TimelineSettledPresentation.Defer -> Row(
            record.key.identity,
            record.revision,
            ChatRenderItem.Single(
                UiMessage(record.key.identity.value, "assistant", "", timestamp = ""),
                GroupPosition.None, keyOverride = replacementKey(record.key.identity),
            ),
            TimelineBodyReference(
                owner.selection.scope,
                record.key,
                requireNotNull(record.pointer) { "Deferred body requires a pointer" },
                record.contentType,
                record.revision,
            ),
            residentEvents = listOf(TimelineResidentEvent(record.key.identity, record.revision, "", "")),
        )
        TimelineSettledPresentation.Drop -> error("Dropped records must not reach projection")
    }

    companion object {
        /** Missing search targets fall back to a usable tail without replacing ingestion generation. */
        suspend fun open(
            coordinator: CanonicalTimelineCoordinator,
            owner: CanonicalTimelineCoordinator.Owner,
            parentScope: CoroutineScope,
            target: TimelineMessageId? = null,
            settledProjectionAdapter: TimelineSettledProjectionAdapter = DefaultTimelineSettledProjectionAdapter,
        ): CanonicalTimelinePresentation {
            val requested = coordinator.attach(owner, target)
            val lease = requested ?: checkNotNull(coordinator.attach(owner))
            return try {
                CanonicalTimelinePresentation(
                    coordinator, lease, parentScope, if (requested == null) target?.value else null, settledProjectionAdapter,
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) { coordinator.detach(lease) }
                throw failure
            }
        }
    }
}
