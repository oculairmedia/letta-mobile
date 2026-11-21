package com.letta.mobile.data.timeline

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.filter
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.timeline.handover.HandedOverPrompts
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.util.TimelineOpenTrace
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.plus
import kotlinx.datetime.Instant

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

    internal val conversationId = ConversationId(owner.selection.scope.conversationId)
    private val firstFrame = TimelineOpenTrace.FirstFrameGate(conversationId)

    /** Whether an open of this conversation is being traced; lets a caller skip building trace work. */
    val isOpenTraced: Boolean get() = TimelineOpenTrace.tracking()

    /** Called from each draw of the list: marks the first frame that shows [hasRows]. Costs one read when untraced. */
    fun traceFirstFrame(hasRows: Boolean) = firstFrame.onDraw(hasRows)

    /**
     * Notes a list replacement and, while the open can still settle, polls for its quiet period.
     * Returns when it settles or gives up, or is cancelled by the next replacement.
     */
    suspend fun traceOpenUntilSettled() {
        TimelineOpenTrace.listReplaced(conversationId)
        while (currentCoroutineContext().isActive && TimelineOpenTrace.isActive(conversationId)) {
            delay(TimelineOpenTrace.SETTLE_QUIET_MS / SETTLE_POLLS_PER_QUIET_PERIOD)
            TimelineOpenTrace.settleIfQuiet(conversationId)
        }
    }
    /**
     * What the list holds, published as ONE value. The live projection drains the prompt by otid and
     * the reply by server id; held in separate flows, a combine could see the new otids before the
     * new rows and draw a frame with the prompt drained but its run-less reply still live, ungrouped
     * beside the settled run that already holds it.
     */
    private val resident = MutableStateFlow(ResidentState())
    // Written by the live projection and read by paging's projection; guarded by its own lock.
    private val streamedKeyAliasLock = SynchronizedObject()
    private val streamedKeyAliases = HashMap<TimelineMessageId, String>()
    private val adoptions = SettledLiveAdoptions()

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
        /**
         * Every id a live row may know this row's records by: server ids, identities, otids and the
         * stream ids aliased to them when the row was projected.
         */
        val liveNames: Set<String> = emptySet(),
        /** By tool call id, the stored bodies of skill instructions this row's skill calls own; read with [readTextWindow]. */
        val skillInstructionBodies: Map<String, TimelineBodyReference> = emptyMap(),
    ) {
        /**
         * Whether this row holds every record [live] draws, so it can take [live]'s place in the
         * list. A row that names nothing (an optimistic prompt) stands in for the live row under its key.
         */
        fun holdsAll(live: ChatRenderItem): Boolean = liveNames.isEmpty() || live.isWhollyNamedBy(liveNames)
    }

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
            resident.update { it.copy(presented = emptyMap()) }
        } finally {
            withContext(NonCancellable) { coordinator.detach(lookup) }
        }
        return true
    }

    /** CRITICAL memory pressure drops every resident page but the one at the reading position. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val settledRows: Flow<PagingData<Row>> = anchor.flatMapLatest { key ->
        owner.session.paging(owner.selection, key, settledProjectionAdapter, MemoryPressure.criticalTrims()).map { page ->
            page.filter { it.preparedPresentation !is TimelineSettledPresentation.Drop }
                .map { record -> project(record, requireNotNull(record.preparedPresentation)) }
        }
    }.cachedIn(scope)

    // A current send stays in the live overlay through its normal echo handover. Only a prompt
    // older than resident history needs insertion; admitting every send here regenerates Paging
    // on append/confirmation and lets a stale pending snapshot hide the streamed echo.
    private val pendingInHistory = combine(owner.session.pending, resident) { pending, residents ->
        pending.filter { (parseTimestampEpochMillis(it.sentAt) ?: 0L) < residents.newestTimestamp }
    }.distinctUntilChanged()

    /** Pending prompts occupy their send-time position in the same paged list as history. */
    val settled: Flow<PagingData<Row>> = combine(settledRows, pendingInHistory) { rows, pending ->
        rows.insertPending(pending, owner.selection.scope)
    }

    /** Retry only this conversation's failed prompt, retaining its original transport identity. */
    suspend fun retry(identity: MessageId): Boolean {
        check(job.isActive) { "Presentation closed" }
        return coordinator.retryPending(owner, identity)
    }

    /** Explicitly discard only this conversation's failed prompt. */
    suspend fun dismiss(identity: MessageId): Boolean {
        check(job.isActive) { "Presentation closed" }
        // Dismissed, the prompt is meant to leave: the overlay does not hold it past its record.
        sentPrompts.forget(identity.value)
        return coordinator.dismissPending(owner, identity)
    }

    // Bounded by pending storage: pruned to the records still awaiting their durable echo. Each
    // otid remembers the turn whose overlay echoed it.
    private val echoedOtids = mutableMapOf<String, TimelineLiveFence>()

    private val mutableLive = MutableStateFlow<List<ChatRenderItem>>(emptyList())

    // Sent prompts whose durable copy has landed (so they left the pending store) but is not on
    // screen yet. Read and written only by the live projection's collector.
    private val sentPrompts = HandedOverPrompts<CanonicalPendingLocalStore.Record>(
        otidOf = { it.otid },
        sentAtMillisOf = { parseTimestampEpochMillis(it.sentAt) },
    )

    /**
     * Published from this presentation's own scope and cancelled with it. Sharing eagerly through
     * stateIn would tie the projection's lifetime to whatever scope was handed in, which is the
     * caller's to cancel, not this presentation's.
     */
    val live: StateFlow<List<ChatRenderItem>> = mutableLive.asStateFlow()

    // Durability alone is not presentation: retain live until the settled ledger is at the turn's revision.
    private val liveProjection: Flow<List<ChatRenderItem>> = createLiveProjection()

    private data class ResidentState(
        val presented: Map<TimelineMessageId, Long> = emptyMap(),
        val otids: Set<String> = emptySet(),
        val serverIds: Set<String> = emptySet(),
        val newestTimestamp: Long = 0L,
    )

    private fun createLiveProjection(): Flow<List<ChatRenderItem>> {
        val mergedPublications = combine(owner.session.live, owner.session.settling) { live, settling ->
            settling + listOfNotNull(live)
        }
        return combine(
            mergedPublications,
            owner.session.pending,
            resident,
        ) { publications, pending, residentSnapshot ->
            projectLiveItems(publications, pending, residentSnapshot)
        }
    }

    private fun projectLiveItems(
        publications: List<TimelineLivePublication>,
        pending: List<CanonicalPendingLocalStore.Record>,
        residentState: ResidentState,
    ): List<ChatRenderItem> {
        publications.forEach(::cacheStreamedAliases)
        val events = filterUnsettledEvents(publications, residentState)
        val shownPending = sentPrompts.hold(pending, residentState.otids, residentState.newestTimestamp)
        updateEchoedOtids(publications, shownPending)
        val optimistic = shownPending.filterNot { it.otid in echoedOtids || it.otid in residentState.otids }
            .map { it.toRenderItem(owner.selection.scope.agentId) }
        val activeMessages = projectLiveMessages(publications, events)
        val active = buildChatRenderModel(
            // A run-less reply settles as its prompt's run (aggregatePreparedRuns); the live render
            // takes the same shape, or the settled row could not take the live row's place and the
            // reply would be drawn twice and then jump at settle (letta-mobile-bglj6.1.12).
            messages = activeMessages,
            mode = ChatDisplayMode.Interactive,
            activeAgentId = owner.selection.scope.agentId,
        // A live run is keyed by its own records, as its settled copy is: never `run-<id>` because the
        // block happens to be the run's only one in this snapshot, a key the settled part of a
        // partly durable run could also compute.
        ).renderItems.map { it.keyedByOwnRecords() }
        return positionPendingInLive(active, optimistic.asReversed())
    }

    /** Derive run ownership before resident prompts leave the overlay, within each turn's fence. */
    private fun projectLiveMessages(
        publications: List<TimelineLivePublication>,
        events: List<TimelineEvent.Confirmed>,
    ): List<UiMessage> {
        val receipts = CanvasArtifactReceipts.attach(events)
        val activeKeys = events.mapTo(hashSetOf()) { it.serverId to it.messageType }
        return publications.flatMap { publication ->
            val messages = publication.block.events.mapNotNull { event ->
                timelineEventToUiMessage(
                    event, owner.selection.scope.agentId,
                    receipts[CanvasArtifactReceipts.eventKey(event)].orEmpty(),
                )?.let { (event.serverId to event.messageType) to it }
            }
            messages.map { it.second }.withPromptOwnedRunIds().filterIndexed { index, _ ->
                messages[index].first in activeKeys
            }
        }
    }

    private fun filterUnsettledEvents(
        publications: List<TimelineLivePublication>,
        residentState: ResidentState,
    ): List<TimelineEvent.Confirmed> = publications.flatMap { it.overlayEvents(residentState.presented) }
        .filterNot { event ->
            (event.otid.isNotBlank() && event.otid in residentState.otids) || event.serverId in residentState.serverIds
        }

    private fun updateEchoedOtids(
        publications: List<TimelineLivePublication>,
        pending: List<CanonicalPendingLocalStore.Record>,
    ) {
        val fences = publications.mapTo(mutableSetOf()) { it.fence }
        echoedOtids.values.removeAll { it !in fences }
        publications.forEach { current ->
            current.block.events.forEach { if (it.otid.isNotBlank()) echoedOtids[it.otid] = current.fence }
        }
        echoedOtids.keys.retainAll(pending.mapTo(mutableSetOf()) { it.otid })
    }

    private fun cacheStreamedAliases(publication: TimelineLivePublication?) {
        val aliases = publication?.aliases ?: return
        if (aliases.isEmpty()) return
        for ((serverId, canonical) in aliases) {
            if (publication.block.events.any { it.serverId == serverId }) {
                synchronized(streamedKeyAliasLock) { streamedKeyAliases.getOrPut(canonical) { "segment-$serverId" } }
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
        resident.value = ResidentState(
            presented = presented,
            otids = residents.mapNotNullTo(mutableSetOf()) { it.otid.takeIf(String::isNotBlank) },
            serverIds = residents.mapNotNullTo(mutableSetOf()) { it.serverId.takeIf(String::isNotBlank) },
            newestTimestamp = rows.maxOfOrNull { parseTimestampEpochMillis(it.item.boundaryTimestamp) ?: 0L } ?: 0L,
        )
        val fences = owner.session.settling.value.map { it.fence } + listOfNotNull(owner.session.live.value?.fence)
        fences.forEach { fence ->
            scope.launch { coordinator.acknowledgeSettlement(owner, fence, presented) }
        }
    }

    suspend fun readChunk(row: Row, offset: Long, maxBytes: Int): TimelineBodyChunk =
        readChunk(requireNotNull(row.deferred) { "Row has no deferred body" }, offset, maxBytes)

    private suspend fun readChunk(reference: TimelineBodyReference, offset: Long, maxBytes: Int): TimelineBodyChunk {
        check(job.isActive) { "Presentation closed" }
        return owner.session.engine.resolveBodyChunk(owner.selection, reference, offset, maxBytes)
    }

    suspend fun readTextWindow(
        row: Row,
        field: TimelineSemanticField,
        scalarOffset: Long,
        decodeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): TimelineSemanticWindowResult =
        readTextWindow(requireNotNull(row.deferred) { "Row has no deferred body" }, field, scalarOffset, decodeDispatcher)

    /** One window of a stored body this presentation's rows point at: a deferred row's, or a skill's instructions. */
    suspend fun readTextWindow(
        reference: TimelineBodyReference,
        field: TimelineSemanticField,
        scalarOffset: Long,
        decodeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): TimelineSemanticWindowResult = withContext(decodeDispatcher) {
        check(job.isActive) { "Presentation closed" }
        val result = TimelineSemanticBodyWindow.read(
            reference = reference, field = field, scalarOffset = scalarOffset,
            budget = TimelineSemanticBudget(maxInputBytes = 2L * 1024 * 1024),
        ) { offset, size -> readChunk(reference, offset, size) }
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

    /**
     * A settled row takes over the live row it replaces - key, run id, turn latency - so the final
     * live render and the settled render of one turn are identical. Rows that replace no live row
     * keep a run's own key or a segment key. Whichever key a row was first drawn with, it keeps for
     * this presentation's life (SettledLiveAdoptions).
     */
    private fun ChatRenderItem.withSettledKey(
        identity: TimelineMessageId,
        residents: List<TimelineResidentEvent>,
    ): ChatRenderItem {
        // The persisted user echo keeps the optimistic slot even without a streamed alias.
        if (this is ChatRenderItem.Single && hasOptimisticUserIdentity()) {
            return copy(keyOverride = "msg-${message.clientMessageId}")
        }
        val own = if (isRunItem) this else withKeyOverride(replacementKey(identity))
        return adoptions.keyed(own, identity, residents, ::liveSnapshot)
    }

    private fun ChatRenderItem.Single.hasOptimisticUserIdentity(): Boolean =
        message.role == "user" && !message.clientMessageId.isNullOrBlank()

    /** Every id the live and settling publications know [this] by. */
    private fun List<TimelineResidentEvent>.liveNames(): Set<String> {
        val aliases = (owner.session.settling.value + listOfNotNull(owner.session.live.value))
            .fold(emptyMap<String, TimelineMessageId>()) { all, publication -> all + publication.aliases }
        return flatMapTo(mutableSetOf()) { it.namesKnownTo(aliases) }
    }

    private fun liveSnapshot(): Pair<List<ChatRenderItem>, Map<String, TimelineMessageId>> =
        mutableLive.value to owner.session.live.value?.aliases.orEmpty()

    /** Preserve the LazyColumn slot while an aliased streamed row becomes its canonical ledger row. */
    private fun replacementKey(identity: TimelineMessageId): String {
        synchronized(streamedKeyAliasLock) { streamedKeyAliases[identity] }?.let { return it }
        val live = owner.session.live.value
        val streamedId = live?.aliases?.entries?.singleOrNull { (serverId, canonical) ->
            canonical == identity && live.block.events.any { it.serverId == serverId }
        }?.key
        val key = "segment-${streamedId ?: identity.value}"
        if (streamedId != null) {
            synchronized(streamedKeyAliasLock) { streamedKeyAliases[identity] = key }
        }
        return key
    }

    private fun project(record: TimelineSettledRecord, presentation: TimelineSettledPresentation): Row = when (presentation) {
        is TimelineSettledPresentation.Render -> {
            val residents = presentation.residentEvents.ifEmpty {
                listOf(TimelineResidentEvent(record.key.identity, record.revision, presentation.event.otid, presentation.event.serverId))
            }
            val item = presentation.item.settledToolCalls().withSettledKey(record.key.identity, residents)
            Row(
                record.key.identity,
                record.revision,
                item,
                otid = presentation.event.otid,
                serverId = presentation.event.serverId,
                residentEvents = residents,
                liveNames = residents.liveNames() + item.messagesInOrder().mapNotNull { it.clientMessageId },
                skillInstructionBodies = presentation.skillInstructionBodies,
            )
        }
        is TimelineSettledPresentation.Defer -> deferredRow(record)
        TimelineSettledPresentation.Drop -> error("Dropped records must not reach projection")
    }

    private fun deferredRow(record: TimelineSettledRecord): Row {
        val residents = listOf(TimelineResidentEvent(record.key.identity, record.revision, "", ""))
        return Row(
            record.key.identity,
            record.revision,
            ChatRenderItem.Single(
                UiMessage(record.key.identity.value, "assistant", "", timestamp = record.key.ledgerTimestamp()),
                GroupPosition.None, keyOverride = replacementKey(record.key.identity),
            ),
            TimelineBodyReference(
                owner.selection.scope,
                record.key,
                requireNotNull(record.pointer) { "Deferred body requires a pointer" },
                record.contentType,
                record.revision,
            ),
            residentEvents = residents,
            liveNames = residents.liveNames(),
        )
    }

    companion object {
        /**
         * The list one read of [live] and the presented [settled] slots (null for a placeholder)
         * renders, with no record drawn twice. A live row draws only the records no resident settled
         * row draws yet, whatever key that row has (a settled row first drawn before the live frame
         * for its records arrived, after a reconnect catch-up, keeps its own key), and is hidden once
         * none are left. A live row that keeps records under a key a settled row holds keeps that key,
         * and the settled row is listed as `<key>~part` (TimelineRowAssembly.assemble).
         */
        fun assemble(live: List<ChatRenderItem>, settled: List<Row?>): TimelineRowAssembly {
            val drawnNames = settled.flatMapTo(HashSet()) { it?.liveNames.orEmpty() }
            val shown = live.mapNotNull { it.withoutRecordsNamedBy(drawnNames) }
            return TimelineRowAssembly.assemble(
                shown,
                TimelineRowAssembly.Settled(settled.map { it?.item?.key }) { index, item -> settled[index]?.holdsAll(item) ?: true },
            )
        }

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
                ).also {
                    TimelineOpenTrace.mark(it.conversationId, TimelineOpenTrace.Stage.PresentationBound)
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) { coordinator.detach(lease) }
                throw failure
            }
        }
    }
}

/**
 * A deferred row is never decoded, but its ledger order is the message time,
 * so it still dates itself. Blank, it reset the day divider and the next row opened "Today" again.
 */
private fun TimelinePageKey.ledgerTimestamp(): String =
    if (order > 0) Instant.fromEpochMilliseconds(order).toString() else ""

private const val SETTLE_POLLS_PER_QUIET_PERIOD = 5
