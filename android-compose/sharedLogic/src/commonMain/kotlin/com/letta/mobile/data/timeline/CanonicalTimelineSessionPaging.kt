package com.letta.mobile.data.timeline

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * One Paging generation per selection. A durable revision invalidates the active source in place, so
 * Paging swaps loaders while keeping the presented list on screen. Rebuilding the [Pager] instead would
 * hand the UI a fresh [PagingData], resetting the presenter to zero rows: that blanks the list and
 * collapses the resident set the live overlay subtracts against, which double-renders every settled row.
 */
@OptIn(ExperimentalPagingApi::class)
fun CanonicalTimelineSession.paging(
    selection: TimelineEngineSelection,
    anchor: TimelinePageKey? = selection.anchor,
    settledProjectionAdapter: TimelineSettledProjectionAdapter = DefaultTimelineSettledProjectionAdapter,
    trims: Flow<Unit> = emptyFlow(),
): Flow<PagingData<TimelineSettledRecord>> {
    val active = atomic<TimelineLedgerPagingSource?>(null)
    return createTimelinePager(selection, active, anchor, settledProjectionAdapter).flow
        .observePublications(selection, active, trims, publication)
}

/** Creates the Pager and registers each source for revision and trim observation. */
@OptIn(ExperimentalPagingApi::class)
internal fun CanonicalTimelineSession.createTimelinePager(
    selection: TimelineEngineSelection,
    active: AtomicRef<TimelineLedgerPagingSource?>,
    anchor: TimelinePageKey? = selection.anchor,
    settledProjectionAdapter: TimelineSettledProjectionAdapter = DefaultTimelineSettledProjectionAdapter,
): Pager<TimelinePageKey, TimelineSettledRecord> {
    return Pager(
        config = PagingConfig(
            pageSize = engine.budget.maxMetadataRows, enablePlaceholders = false,
            initialLoadSize = engine.budget.maxMetadataRows, maxSize = engine.budget.maxMetadataRows * 3,
        ),
        initialKey = anchor,
        remoteMediator = TimelineHistoryMediator(this, selection),
        pagingSourceFactory = {
            TimelineLedgerPagingSource(engine, selection, selection.scope.agentId, settledProjectionAdapter).also { active.value = it }
        },
    )
}

/** Keeps revision observation testable independently of the page pump's scheduling. */
internal fun Flow<PagingData<TimelineSettledRecord>>.observePublications(
    selection: TimelineEngineSelection,
    active: AtomicRef<TimelineLedgerPagingSource?>,
    trims: Flow<Unit> = emptyFlow(),
    publications: Flow<TimelineEnginePublication>,
): Flow<PagingData<TimelineSettledRecord>> {
    return channelFlow {
        val pump = launch { this@observePublications.collect { send(it) } }
        // Memory pressure replaces the active source in place: Paging reloads one page around the
        // reading position and drops every other page it held, without blanking the list.
        launch {
            trims.collect {
                active.value?.invalidate()
                Telemetry.event("TimelinePaging", "generation.trimmed", level = Telemetry.Level.DEBUG)
            }
        }
        // A superseded selection retires this generation without emitting, matching the prior contract.
        publications.distinctUntilChanged { old, new ->
            old.selection === new.selection && old.durableRevision == new.durableRevision
        }.collect { current ->
            if (current.selection !== selection) {
                pump.cancel()
            } else if (active.value?.invalidateIfBehind(current.durableRevision) == true) {
                // The pump may have loaded an older revision before this collector's first emission.
                // A source that already replaced itself for this revision is current and is left
                // alone. Every replaced generation redraws the list; a capture names what moved it.
                Telemetry.event(
                    "TimelinePaging", "generation.replaced",
                    "revision" to current.durableRevision, level = Telemetry.Level.DEBUG,
                )
            }
        }
    }
}

/**
 * Both ends of the list reach the network. Older history walks the durable continuation backwards;
 * the newest end reconciles a bounded recent page, which is how a message authored on another client
 * enters this ledger at all.
 *
 * Without the prepend, new content could only arrive through live ingest - the stream this client
 * happens to be watching, plus its own sends - so a conversation went permanently stale the moment
 * it was written to from somewhere else, and no amount of scrolling could recover it.
 */
@OptIn(ExperimentalPagingApi::class)
internal class TimelineHistoryMediator(
    private val session: CanonicalTimelineSession,
    private val selection: TimelineEngineSelection,
) : RemoteMediator<TimelinePageKey, TimelineSettledRecord>() {
    override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<TimelinePageKey, TimelineSettledRecord>,
    ): MediatorResult {
        if (loadType == LoadType.PREPEND) return refreshNewest()
        return try {
            if (!session.engine.hasOlderHistory(selection)) return MediatorResult.Success(true)
            when (session.loadOlder(selection)) {
                TimelineEnginePageOutcome.Applied -> MediatorResult.Success(!session.engine.hasOlderHistory(selection))
                TimelineEnginePageOutcome.Stale -> MediatorResult.Error(IllegalStateException("Stale history request"))
                TimelineEnginePageOutcome.NoProgress -> MediatorResult.Error(IllegalStateException("History cursor did not advance"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MediatorResult.Error(failure)
        }
    }

    /**
     * The recent-tail read, which deliberately does not consume the older-history cursor: the two
     * ends of this list are independent walks and must not spend each other's state.
     *
     * End of pagination is reported when the page added nothing, which is also what stops this from
     * spinning. Applying rows bumps the durable revision, the source is invalidated, and the newest
     * end is asked again - until a page appends nothing and the walk settles.
     */
    private suspend fun refreshNewest(): MediatorResult = try {
        reconcileRecentTail()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MediatorResult.Error(failure)
    }

    /**
     * A newer [CanonicalTimelineSession.reconcileRecentDetailed] for the same selection supersedes the
     * in-flight reconcile ([CanonicalTimelineEngine.pendingReconcile]) and answers [TimelineEnginePageOutcome.Stale]
     * with no durable effect. That is not a transport failure: the winner already applied (or will apply)
     * the page. Retry while this paging generation still owns the active selection; retire orphaned
     * generations without a sticky prepend error.
     */
    private suspend fun reconcileRecentTail(): MediatorResult {
        var retries = 0
        while (true) {
            if (session.publication.value.selection !== selection) {
                return MediatorResult.Success(endOfPaginationReached = true)
            }
            val result = session.reconcileRecentDetailed(selection)
            when (result.outcome) {
                TimelineEnginePageOutcome.Applied -> return MediatorResult.Success(result.appended == 0)
                TimelineEnginePageOutcome.NoProgress -> return MediatorResult.Success(endOfPaginationReached = true)
                TimelineEnginePageOutcome.Stale -> {
                    if (retries++ >= STALE_RECENT_RETRIES) {
                        return MediatorResult.Error(IllegalStateException("Stale recent request"))
                    }
                    yield()
                }
            }
        }
    }

    private companion object {
        private const val STALE_RECENT_RETRIES = 5
    }
}
