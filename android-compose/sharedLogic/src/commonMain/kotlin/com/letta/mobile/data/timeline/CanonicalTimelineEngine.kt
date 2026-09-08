package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
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
    private val mutablePublication = MutableStateFlow(TimelineEnginePublication())
    val publication = mutablePublication.asStateFlow()
    private var liveFence: TimelineLiveFence? = null
    private var liveReduction: TimelineReducerState? = null
    private val mutableLive = MutableStateFlow<TimelineLivePublication?>(null)
    val live = mutableLive.asStateFlow()

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        check(mutableLive.value?.settlementRevision == null) { "Settlement is awaiting presentation" }
        check(sequence < Long.MAX_VALUE)
        TimelineLiveFence(selection, TimelineRequestId((++sequence).toString())).also {
            liveFence = it
            liveReduction = TimelineReducerState(Timeline(conversationId = selection.scope.conversationId))
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
        val terminal = frame == TimelineStreamFrame.Done
        val identities = mutableMapOf<TimelineMessageId, TimelineMessageId>()
        val revision = if (terminal) store.transaction(fence.selection.scope) {
            val exact = writer as? TimelineExactCanonicalWriter ?: error("Live reduction requires exact shared writer")
            var changed = false
            for (event in events) {
                changed = exact.mergeEvent(this, event) || changed
                identities[TimelineMessageId(event.serverId)] = exact.canonicalIdentity(this, event.serverId, event.otid)
            }
            if (changed) nextRevision() else checkpoint().revision
        } else null
        liveReduction = next
        mutableLive.value = TimelineLivePublication(fence, TimelineLiveBlock(emptyList(), terminal, events), revision, identities)
        if (revision != null) mutablePublication.value = TimelineEnginePublication(fence.selection, revision)
        true
    }

    suspend fun publishLive(fence: TimelineLiveFence, block: TimelineLiveBlock): Boolean = mutex.withLock {
        if (liveFence !== fence || fence.selection !== mutablePublication.value.selection) return@withLock false
        if (mutableLive.value?.block?.terminal == true) return@withLock false
        validate(TimelineRemotePageResult.Page(
            fence.requestId, fence.selection.generation, block.records, null, false,
            block.records.sumOf { it.encodedBodyBytes },
        ))
        val identities = mutableMapOf<TimelineMessageId, TimelineMessageId>()
        val revision = if (block.terminal) store.transaction(fence.selection.scope) {
            val before = checkpoint()
            var changed = false
            for (record in block.records) {
                currentCoroutineContext().ensureActive()
                changed = writer.merge(this, record) || changed
                val event = record.message.toTimelineEvent(0.0)
                if (writer is TimelineExactCanonicalWriter && event != null) {
                    identities[record.identity] = writer.canonicalIdentity(this, event.serverId, event.otid)
                }
            }
            currentCoroutineContext().ensureActive()
            if (changed) nextRevision() else before.revision
        } else null
        mutableLive.value = TimelineLivePublication(fence, block, revision, identities)
        if (revision != null) mutablePublication.value = TimelineEnginePublication(fence.selection, revision)
        true
    }

    /** Runtime-only release when no viewport is attached; never discard an uncommitted live block. */
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

    /** Host acknowledges only after every terminal identity is resident at the committed revision. */
    suspend fun acknowledgeSettlement(
        fence: TimelineLiveFence,
        presented: Map<TimelineMessageId, Long>,
    ): Boolean = mutex.withLock {
        val current = mutableLive.value ?: return@withLock false
        val revision = current.settlementRevision ?: return@withLock false
        if (current.fence !== fence || current.block.records.any {
                (presented[current.settlementIdentities[it.identity] ?: it.identity] ?: -1) < revision
            } || current.unpresentedEvents(presented).isNotEmpty()
        ) {
            return@withLock false
        }
        mutableLive.value = null
        liveFence = null
        liveReduction = null
        true
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
