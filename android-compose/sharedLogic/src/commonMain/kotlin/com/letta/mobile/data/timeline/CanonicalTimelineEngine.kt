package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A shared semantic writer, never implemented by the platform persistence backend. */
fun interface TimelineCanonicalWriter {
    /** Resolve exact evidence and write ledger/evidence atomically; return whether either changed. */
    suspend fun merge(transaction: TimelineStoreTransaction, record: TimelineRemoteRecord): Boolean
}

data class TimelineEngineSelection(
    val scope: TimelineScope,
    val generation: TimelineSelectionGeneration,
    val anchor: TimelinePageKey?,
)

data class TimelineEngineRequest(val selection: TimelineEngineSelection, val remote: TimelineRemotePageRequest)

data class TimelineEnginePublication(
    val selection: TimelineEngineSelection? = null,
    val durableRevision: Long = 0,
)

sealed interface TimelineEngineOpen {
    data class Opened(val selection: TimelineEngineSelection) : TimelineEngineOpen
    data object Disabled : TimelineEngineOpen
    data object MissingTarget : TimelineEngineOpen
}

enum class TimelineEnginePageOutcome { Applied, Stale, NoProgress }

data class TimelineEngineReconcileResult(val outcome: TimelineEnginePageOutcome, val appended: Int = 0, val committedSequence: Long? = null)

/**
 * Serialized shadow coordinator. The mutex covers suspending storage transactions, not just dispatch.
 * No host may enable this until an exact shared semantic writer and transport binding are supplied.
 */
class CanonicalTimelineEngine(
    private val store: TimelineBoundedStore,
    private val writer: TimelineCanonicalWriter,
    val budget: TimelinePageBudget = TimelinePageBudget(64, 2L * 1024 * 1024),
    private val enabled: Boolean = false,
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var sequence = 0L
    private var pending: TimelineEngineRequest? = null
    private var pendingReconcile: TimelineEngineRequest? = null
    private val mutablePublication = MutableStateFlow(TimelineEnginePublication())
    val publication = mutablePublication.asStateFlow()
    private var liveFence: TimelineLiveFence? = null
    private var liveReduction: TimelineReducerState? = null
    private var liveReturns = emptySet<String>()
    private val mutableLive = MutableStateFlow<TimelineLivePublication?>(null)
    val live = mutableLive.asStateFlow()

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        // A committed settlement already belongs to the durable ledger. Starting another turn
        // drops only its resident overlay; Paging observes durableRevision and canonical identities.
        // Old acknowledgments remain fenced by the publication's fence, not a growing body queue.
        check(sequence < Long.MAX_VALUE)
        TimelineLiveFence(selection, TimelineRequestId((++sequence).toString())).also {
            // A response fetched before this run cannot repair the post-run timeline.
            pendingReconcile = null
            liveFence = it
            liveReduction = TimelineReducerState(Timeline(conversationId = selection.scope.conversationId))
            liveReturns = emptySet()
            mutableLive.value = null
        }
    }

    suspend fun ingest(fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean = mutex.withLock {
        if (liveFence !== fence || fence.selection !== mutablePublication.value.selection ||
            mutableLive.value?.settlementRevision != null
        ) return@withLock false
        val previous = checkNotNull(liveReduction)
        val next = when (frame) {
            is TimelineStreamFrame.Message -> {
                val output = reduceStreamFrame(TimelineReducerInput(previous.timeline, frame.message,
                    previous.pendingToolReturnsByCallId, agentId = fence.selection.scope.agentId))
                previous.copy(timeline = output.next, pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId)
            }
            TimelineStreamFrame.Heartbeat -> return@withLock true
            TimelineStreamFrame.Done -> previous
            is TimelineStreamFrame.RawEvent -> error("Raw events must be decoded by the transport before ingest")
        }
        val events = next.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        require(events.size <= budget.maxMetadataRows) { "Live block row budget exceeded" }
        var liveBytes = 0L
        for (event in events) {
            liveBytes += TimelineSnapshotCodec.json.encodeToString(
                StoredTimelineEvent.serializer(), event.toStoredTimelineEvent(),
            ).encodeToByteArray().size
            require(liveBytes <= budget.maxDecodedBodyBytes) { "Live block byte budget exceeded" }
        }
        val returnedId = ((frame as? TimelineStreamFrame.Message)?.message as? com.letta.mobile.data.model.ToolReturnMessage)
            ?.toolReturn?.toolCallId?.takeIf { it.isNotBlank() }
        val nextReturns = if (returnedId == null) liveReturns else liveReturns + returnedId
        require(nextReturns.size <= budget.maxMetadataRows) { "Live return index budget exceeded" }
        val terminal = frame == TimelineStreamFrame.Done
        // Sync/reconcile is the single durable writer; a stream-only identity would double the row.
        // Terminal frames only name the first revision that can carry this turn, so the overlay
        // knows when the settled ledger has caught up. Nothing here commits.
        val revision = if (terminal) store.read(fence.selection.scope) { checkpoint().revision + 1 } else null
        liveReduction = next
        liveReturns = nextReturns
        mutableLive.value = TimelineLivePublication(fence, TimelineLiveBlock(emptyList(), terminal, events), revision)
        true
    }

    suspend fun publishLive(fence: TimelineLiveFence, block: TimelineLiveBlock): Boolean = mutex.withLock {
        if (liveFence !== fence || fence.selection !== mutablePublication.value.selection) return@withLock false
        if (mutableLive.value?.block?.terminal == true) return@withLock false
        validate(TimelineRemotePageResult.Page(
            fence.requestId, fence.selection.generation, block.records, null, false,
            block.records.sumOf { it.encodedBodyBytes },
        ))
        val revision = if (block.terminal) store.transaction(fence.selection.scope) {
            val before = checkpoint()
            var changed = false
            for (record in block.records) {
                currentCoroutineContext().ensureActive()
                changed = writer.merge(this, record) || changed
            }
            currentCoroutineContext().ensureActive()
            if (changed) nextRevision() else before.revision
        } else null
        mutableLive.value = TimelineLivePublication(fence, block, revision)
        if (revision != null) mutablePublication.value = TimelineEnginePublication(fence.selection, revision)
        true
    }

    /** Runtime-only release when no viewport is attached; never discard a still-streaming block. */
    internal suspend fun releaseUnobservedSettlement(fence: TimelineLiveFence): Boolean = mutex.withLock {
        if (liveFence !== fence) return@withLock false
        if (fence.selection !== mutablePublication.value.selection) return@withLock false
        val current = mutableLive.value ?: return@withLock false
        if (current.settlementRevision == null) return@withLock false
        mutableLive.value = null
        liveFence = null
        liveReduction = null
        true
    }

    /** Host acknowledges only once the settled ledger it renders carries this turn's identity. */
    suspend fun acknowledgeSettlement(
        fence: TimelineLiveFence,
        presented: Map<TimelineMessageId, Long>,
    ): Boolean = mutex.withLock {
        val current = mutableLive.value ?: return@withLock false
        if (current.fence !== fence) return@withLock false
        val resolved = resolvePresented(fence.selection.scope, current, presented)
        if (!current.isSettled(resolved)) return@withLock false
        mutableLive.value = null
        liveFence = null
        liveReduction = null
        true
    }

    suspend fun resolvePresented(
        fence: TimelineLiveFence,
        presented: Map<TimelineMessageId, Long>,
    ): Map<TimelineMessageId, Long> = mutex.withLock {
        val current = mutableLive.value ?: return@withLock presented
        if (current.fence !== fence) return@withLock presented
        resolvePresented(fence.selection.scope, current, presented)
    }

    private suspend fun resolvePresented(
        scope: TimelineScope,
        publication: TimelineLivePublication,
        presented: Map<TimelineMessageId, Long>,
    ): Map<TimelineMessageId, Long> {
        if (publication.block.events.isEmpty() || presented.isEmpty()) return presented
        val exact = writer as? TimelineExactCanonicalWriter ?: return presented
        val resolved = presented.toMutableMap()
        var changed = false
        store.read(scope) {
            for ((key, rev) in presented) {
                val canonical = exact.canonicalIdentity(this, key.value, "")
                if (canonical != key && canonical !in resolved) {
                    resolved[canonical] = rev
                    changed = true
                }
            }
            for (event in publication.block.events) {
                val canonical = exact.canonicalIdentity(this, event.serverId, event.otid)
                val eventId = TimelineMessageId(event.serverId)
                val otidId = event.otid.takeIf { it.isNotBlank() }?.let { TimelineMessageId(it) }
                val rev = resolved[canonical] ?: resolved[eventId] ?: otidId?.let { resolved[it] }
                if (rev != null) {
                    if (canonical !in resolved) {
                        resolved[canonical] = rev
                        changed = true
                    }
                    if (eventId !in resolved) {
                        resolved[eventId] = rev
                        changed = true
                    }
                    if (otidId != null && otidId !in resolved) {
                        resolved[otidId] = rev
                        changed = true
                    }
                }
            }
        }
        return if (changed) resolved else presented
    }

    suspend fun open(scope: TimelineScope, target: TimelineMessageId? = null): TimelineEngineOpen = mutex.withLock {
        if (!enabled) return@withLock TimelineEngineOpen.Disabled
        val (checkpoint, anchor) = store.read(scope) { checkpoint() to target?.let { locate(it) } }
        if (target != null && anchor == null) return@withLock TimelineEngineOpen.MissingTarget
        check(generation < Long.MAX_VALUE)
        val selection = TimelineEngineSelection(scope, TimelineSelectionGeneration(++generation), anchor)
        pending = null
        liveFence = null
        liveReduction = null
        mutableLive.value = null
        mutablePublication.value = TimelineEnginePublication(selection, checkpoint.revision)
        TimelineEngineOpen.Opened(selection)
    }

    suspend fun hasOlderHistory(selection: TimelineEngineSelection): Boolean = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        store.read(selection.scope) { checkpoint().hasMore }
    }

    suspend fun beginPage(selection: TimelineEngineSelection): TimelineEngineRequest = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        val checkpoint = store.read(selection.scope) { checkpoint() }
        check(checkpoint.hasMore) { "History exhausted" }
        check(sequence < Long.MAX_VALUE)
        val remote = TimelineRemotePageRequest(
            selection.scope, TimelineRequestId((++sequence).toString()), selection.generation,
            TimelineRemoteOrder.NewestFirst, checkpoint.continuation ?: TimelineContinuation.Initial, budget,
        )
        TimelineEngineRequest(selection, remote).also { pending = it }
    }

    suspend fun applyPage(request: TimelineEngineRequest, page: TimelineRemotePageResult.Page): TimelineEnginePageOutcome =
        mutex.withLock {
            if (pending !== request || request.selection !== mutablePublication.value.selection ||
                page.requestId != request.remote.requestId || page.selectionGeneration != request.selection.generation
            ) return@withLock TimelineEnginePageOutcome.Stale
            validate(page)
            // Refuse an unadvanced cursor even for empty pages; never persist false exhaustion.
            if (page.hasMore && page.nextContinuation == request.remote.continuation) {
                pending = null
                return@withLock TimelineEnginePageOutcome.NoProgress
            }
            val revision = store.transaction(request.selection.scope) {
                val before = checkpoint()
                check((before.continuation ?: TimelineContinuation.Initial) == request.remote.continuation) {
                    "Durable cursor changed during request"
                }
                var changed = false
                for (record in page.records) {
                    currentCoroutineContext().ensureActive()
                    changed = writer.merge(this, record) || changed
                }
                val cursorChanged = before.continuation != page.nextContinuation || before.hasMore != page.hasMore
                if (cursorChanged) cursor(page.nextContinuation, page.hasMore)
                currentCoroutineContext().ensureActive()
                if (changed || cursorChanged) nextRevision() else before.revision
            }
            // No suspension after commit: cancellation cannot strand the in-memory watermark.
            mutablePublication.value = TimelineEnginePublication(request.selection, revision)
            pending = null
            TimelineEnginePageOutcome.Applied
        }

    suspend fun beginReconcile(selection: TimelineEngineSelection): TimelineEngineRequest = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        check(sequence < Long.MAX_VALUE)
        val remote = TimelineRemotePageRequest(selection.scope, TimelineRequestId((++sequence).toString()),
            selection.generation, TimelineRemoteOrder.NewestFirst, TimelineContinuation.Initial, budget)
        TimelineEngineRequest(selection, remote).also { pendingReconcile = it }
    }

    /** Recent-tail repair must not advance or exhaust the independent older-history cursor. */
    suspend fun reconcilePage(
        request: TimelineEngineRequest,
        page: TimelineRemotePageResult.Page,
    ): TimelineEnginePageOutcome = reconcilePageDetailed(request, page).outcome

    suspend fun reconcilePageDetailed(
        request: TimelineEngineRequest,
        page: TimelineRemotePageResult.Page,
    ): TimelineEngineReconcileResult = mutex.withLock {
        if (pendingReconcile !== request || request.selection !== mutablePublication.value.selection ||
            page.requestId != request.remote.requestId || page.selectionGeneration != request.selection.generation
        ) return@withLock TimelineEngineReconcileResult(TimelineEnginePageOutcome.Stale)
        // A settled turn now depends on this path for its durable rows, so only refuse mid-stream.
        if (liveFence != null && mutableLive.value?.settlementRevision == null) {
            return@withLock TimelineEngineReconcileResult(TimelineEnginePageOutcome.NoProgress)
        }
        validate(page)
        val (revision, appended) = store.transaction(request.selection.scope) {
            var changed = false
            var appended = 0
            for (record in page.records) {
                currentCoroutineContext().ensureActive()
                val event = record.message.toTimelineEvent(0.0)
                val identity = if (writer is TimelineExactCanonicalWriter && event != null)
                    writer.canonicalIdentity(this, event.serverId, event.otid) else record.identity
                val existed = locate(identity) != null
                val merged = writer.merge(this, record)
                if (merged && !existed) appended++
                changed = merged || changed
            }
            currentCoroutineContext().ensureActive()
            (if (changed) nextRevision() else checkpoint().revision) to appended
        }
        mutablePublication.value = TimelineEnginePublication(request.selection, revision)
        pendingReconcile = null
        TimelineEngineReconcileResult(TimelineEnginePageOutcome.Applied, appended,
            page.records.mapNotNull { it.message.seqId?.toLong() }.maxOrNull())
    }

    /**
     * Presentation checks explicit suppression without erasing an in-flight page merely
     * because pending-send metadata advanced the checkpoint. Paging owns generation replacement.
     */
    suspend fun isSuppressed(
        selection: TimelineEngineSelection,
        identity: TimelineMessageId,
        revision: Long,
        event: TimelineEvent.Confirmed,
    ): Boolean = mutex.withLock {
        if (selection !== mutablePublication.value.selection) return@withLock true
        store.read(selection.scope) {
            require(revision <= checkpoint().revision) { "Future presentation revision" }
            val exact = writer as? TimelineExactCanonicalWriter ?: error("Exact writer required")
            val canonical = exact.canonicalIdentity(this, event.serverId, event.otid)
            require(identity == canonical) { "Suppression identity mismatch" }
            val bytes = evidence("suppression/server/${identity.value}", 64 * 1024)
            event.messageType == TimelineMessageType.ASSISTANT && bytes != null &&
                TimelineSnapshotCodec.json.decodeFromString(AbandonedAssistantFragmentSuppression.serializer(), bytes.decodeToString()) ==
                event.toAbandonedAssistantFragmentSuppression()
        }
    }

    /** Suppress exact abandoned tail fragments without deleting their raw durable bodies. */
    suspend fun suppressAbandonedTail(
        selection: TimelineEngineSelection,
        runId: String?,
        turnId: String?,
        reason: String,
        candidateRunIds: Set<String>,
    ): Int = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        check(liveFence == null || mutableLive.value?.settlementRevision != null) { "Turn still active" }
        val (revision, count) = store.transaction(selection.scope) {
            val metadata = metadata(TimelineReadPosition.Tail, budget.maxMetadataRows)
            var remaining = budget.maxDecodedBodyBytes
            val events = mutableListOf<TimelineEvent.Confirmed>()
            for (row in metadata.rows.asReversed()) {
                if (row.contentType != "application/vnd.letta.timeline-event+json;version=1") break
                require(row.body.encodedBytes <= remaining && row.body.encodedBytes <= Int.MAX_VALUE) { "Cleanup body budget exceeded" }
                val bytes = ByteArray(row.body.encodedBytes.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val requested = minOf(64 * 1024, bytes.size - offset)
                    val chunk = body(row.body, offset.toLong(), requested)
                    check(chunk.isNotEmpty() && chunk.size <= requested)
                    chunk.copyInto(bytes, offset)
                    offset += chunk.size
                }
                remaining -= bytes.size
                val event = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), bytes.decodeToString())
                val confirmed = event.toConfirmedTimelineEvent()
                if (confirmed.messageType != TimelineMessageType.ASSISTANT) break
                events.add(confirmed)
            }
            // A truncated assistant-only tail could hide the longest prefix owner. Fail closed.
            check(events.size < budget.maxMetadataRows || metadata.older == null) { "Cleanup tail exceeds bounded window" }
            val timeline = Timeline(conversationId = selection.scope.conversationId, events = events.asReversed().toTimelinePersistentList())
            val result = timeline.cleanupAbandonedAssistantFragments(runId, turnId, reason, candidateRunIds)
            var changed = 0
            for (decision in result.suppressions) {
                val id = decision.serverId ?: continue
                val key = "suppression/server/$id"
                val bytes = TimelineSnapshotCodec.json.encodeToString(AbandonedAssistantFragmentSuppression.serializer(), decision).encodeToByteArray()
                if (evidence(key, 64 * 1024)?.contentEquals(bytes) != true) {
                    putEvidence(key, bytes)
                    changed++
                }
            }
            (if (changed > 0) nextRevision() else checkpoint().revision) to changed
        }
        mutablePublication.value = TimelineEnginePublication(selection, revision)
        count
    }

    suspend fun advanceToolSweep(selection: TimelineEngineSelection): Long = mutex.withLock {
        check(selection === mutablePublication.value.selection)
        val (sweepGeneration, revision) = store.transaction(selection.scope) {
            CanonicalToolIndex.advanceGeneration(this) to nextRevision()
        }
        mutablePublication.value = TimelineEnginePublication(selection, revision)
        sweepGeneration
    }

    suspend fun settleToolSweep(selection: TimelineEngineSelection, generation: Long): Int = mutex.withLock {
        check(selection === mutablePublication.value.selection)
        val (revision, count) = store.transaction(selection.scope) {
            if (toolSweepGeneration() != generation) return@transaction checkpoint().revision to 0
            val entries = unresolvedTools(null, budget.maxMetadataRows)
            var remaining = budget.maxDecodedBodyBytes
            var count = 0
            for (entry in entries) {
                val identity = checkNotNull(entry.owner)
                if (!CanonicalToolIndex.stillUnresolved(this, generation, entry.callId, identity)) continue
                val key = checkNotNull(locate(identity)) { "Missing unresolved tool owner" }
                val row = metadata(TimelineReadPosition.Around(key), 1).rows.single { it.key == key }
                require(row.body.encodedBytes <= remaining && row.body.encodedBytes <= Int.MAX_VALUE) { "Tool settlement budget exceeded" }
                val bytes = ByteArray(row.body.encodedBytes.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val limit = minOf(64 * 1024, bytes.size - offset)
                    val chunk = body(row.body, offset.toLong(), limit)
                    check(chunk.isNotEmpty() && chunk.size <= limit)
                    chunk.copyInto(bytes, offset)
                    offset += chunk.size
                }
                remaining -= bytes.size
                val event = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), bytes.decodeToString()).toConfirmedTimelineEvent()
                check(event.toolCalls.any { it.effectiveId == entry.callId }) { "Tool index owner mismatch" }
                val repaired = event.copy(
                    toolReturnContent = event.toolReturnContent ?: DanglingToolCallResolver.NO_RESULT_MESSAGE,
                    toolReturnIsError = if (event.toolReturnContent == null) true else event.toolReturnIsError,
                    toolReturnContentByCallId = (event.toolReturnContentByCallId + (entry.callId to DanglingToolCallResolver.NO_RESULT_MESSAGE)).toTimelinePersistentMap(),
                    toolReturnIsErrorByCallId = (event.toolReturnIsErrorByCallId + (entry.callId to true)).toTimelinePersistentMap(),
                )
                (writer as TimelineExactCanonicalWriter).mergeEvent(this, repaired)
                count++
            }
            (if (count > 0) nextRevision() else checkpoint().revision) to count
        }
        mutablePublication.value = TimelineEnginePublication(selection, revision)
        count
    }

    suspend fun cancelPage(request: TimelineEngineRequest) = mutex.withLock {
        if (pending === request) pending = null
    }

    suspend fun cancelReconcile(request: TimelineEngineRequest) = mutex.withLock {
        if (pendingReconcile === request) pendingReconcile = null
    }

    suspend fun rejectReconcileNoProgress(
        request: TimelineEngineRequest,
        response: TimelineRemotePageResult.NoProgress,
    ): TimelineEnginePageOutcome = mutex.withLock {
        if (pendingReconcile !== request || request.selection !== mutablePublication.value.selection ||
            response.requestId != request.remote.requestId || response.selectionGeneration != request.selection.generation
        ) return@withLock TimelineEnginePageOutcome.Stale
        require(response.continuation == request.remote.continuation)
        pendingReconcile = null
        TimelineEnginePageOutcome.NoProgress
    }

    suspend fun rejectNoProgress(
        request: TimelineEngineRequest,
        response: TimelineRemotePageResult.NoProgress,
    ): TimelineEnginePageOutcome = mutex.withLock {
        if (pending !== request || mutablePublication.value.selection !== request.selection ||
            response.requestId != request.remote.requestId || response.selectionGeneration != request.selection.generation
        ) return@withLock TimelineEnginePageOutcome.Stale
        require(response.continuation == request.remote.continuation)
        pending = null
        TimelineEnginePageOutcome.NoProgress
    }

    suspend fun load(selection: TimelineEngineSelection, position: TimelineReadPosition, maxRows: Int): TimelineBodyPage =
        mutex.withLock {
            check(selection === mutablePublication.value.selection) { "Stale selection" }
            require(maxRows > 0)
            TimelineBoundedReader(store).preview(selection.scope, position, budget.copy(maxMetadataRows = minOf(maxRows, budget.maxMetadataRows)))
        }

    /** Chunk access shares the selection fence; a reference cannot cross conversation ownership. */
    suspend fun resolveBodyChunk(
        selection: TimelineEngineSelection,
        reference: TimelineBodyReference,
        offset: Long,
        maxBytes: Int,
    ): TimelineBodyChunk = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        require(reference.scope == selection.scope) { "Body scope mismatch" }
        TimelineBoundedReader(store).readChunk(reference, offset, maxBytes)
    }

    /** Resolve only the selected row, in one storage snapshot, without following a stale pointer. */
    suspend fun resolveBody(selection: TimelineEngineSelection, record: TimelineSettledRecord): TimelineSettledRecord =
        mutex.withLock {
            check(selection === mutablePublication.value.selection) { "Stale selection" }
            val pointer = record.pointer ?: return@withLock record
            if (!record.isPreview) return@withLock record
            require(pointer.encodedBytes <= budget.maxDecodedBodyBytes && pointer.encodedBytes <= Int.MAX_VALUE) {
                "Body requires chunked presentation"
            }
            store.read(selection.scope) {
                val page = metadata(TimelineReadPosition.Around(record.key), 1)
                check(page.revision == record.revision) { "Stale body revision" }
                val row = page.rows.singleOrNull { it.key == record.key }
                check(row?.body == pointer) { "Stale body pointer" }
                val bytes = ByteArray(pointer.encodedBytes.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val requested = minOf(64 * 1024, bytes.size - offset)
                    val chunk = body(pointer, offset.toLong(), requested)
                    check(chunk.isNotEmpty() && chunk.size <= requested) { "Incomplete body" }
                    chunk.copyInto(bytes, offset)
                    offset += chunk.size
                }
                record.copy(body = bytes)
            }
        }

    suspend fun readBody(selection: TimelineEngineSelection, pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray =
        mutex.withLock {
            check(selection === mutablePublication.value.selection)
            require(offset >= 0 && maxBytes > 0 && maxBytes.toLong() <= budget.maxDecodedBodyBytes)
            store.read(selection.scope) { body(pointer, offset, maxBytes) }.also { require(it.size <= maxBytes) }
        }

    /** Releases resident ownership only. Durable ledger and evidence survive reopen and page dropping. */
    suspend fun release(selection: TimelineEngineSelection) = mutex.withLock {
        if (selection === mutablePublication.value.selection) {
            pending = null
            pendingReconcile = null
            liveFence = null
            liveReduction = null
            mutableLive.value = null
            mutablePublication.value = TimelineEnginePublication()
        }
    }

    private fun validate(page: TimelineRemotePageResult.Page) {
        require(page.records.size <= budget.maxMetadataRows)
        require(page.hasMore == (page.nextContinuation != null))
        require(page.records.map { it.identity }.toSet().size == page.records.size)
        var remaining = budget.maxDecodedBodyBytes
        for (record in page.records) {
            require(record.identity.value == record.message.id)
            require(record.encodedBodyBytes <= remaining)
            remaining -= record.encodedBodyBytes
        }
        require(page.decodedBodyBytes == budget.maxDecodedBodyBytes - remaining)
    }
}
