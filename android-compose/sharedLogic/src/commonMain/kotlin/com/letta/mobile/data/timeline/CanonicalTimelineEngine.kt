package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
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
    private val mutableLive = MutableStateFlow<TimelineLivePublication?>(null)
    val live = mutableLive.asStateFlow()

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = mutex.withLock {
        check(selection === mutablePublication.value.selection) { "Stale selection" }
        check(mutableLive.value?.settlementRevision == null) { "Settlement is awaiting presentation" }
        check(sequence < Long.MAX_VALUE)
        TimelineLiveFence(selection, TimelineRequestId((++sequence).toString())).also {
            liveFence = it
            mutableLive.value = null
        }
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

    /** Host acknowledges only after every terminal identity is resident at the committed revision. */
    suspend fun acknowledgeSettlement(
        fence: TimelineLiveFence,
        presented: Map<TimelineMessageId, Long>,
    ): Boolean = mutex.withLock {
        val current = mutableLive.value ?: return@withLock false
        val revision = current.settlementRevision ?: return@withLock false
        if (current.fence !== fence || current.block.records.any { (presented[it.identity] ?: -1) < revision }) {
            return@withLock false
        }
        mutableLive.value = null
        liveFence = null
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
        mutableLive.value = null
        mutablePublication.value = TimelineEnginePublication(selection, checkpoint.revision)
        TimelineEngineOpen.Opened(selection)
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

    suspend fun load(selection: TimelineEngineSelection, position: TimelineReadPosition, maxRows: Int): TimelineBodyPage =
        mutex.withLock {
            check(selection === mutablePublication.value.selection) { "Stale selection" }
            require(maxRows > 0)
            TimelineBoundedReader(store).load(selection.scope, position, budget.copy(maxMetadataRows = minOf(maxRows, budget.maxMetadataRows)))
        }

    /** Releases resident ownership only. Durable ledger and evidence survive reopen and page dropping. */
    suspend fun release(selection: TimelineEngineSelection) = mutex.withLock {
        if (selection === mutablePublication.value.selection) {
            pending = null
            liveFence = null
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
