package com.letta.mobile.data.timeline

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
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
): Flow<PagingData<TimelineSettledRecord>> {
    val active = AtomicReference<TimelineLedgerPagingSource?>(null)
    val pages = Pager(
        config = PagingConfig(
            pageSize = engine.budget.maxMetadataRows, enablePlaceholders = false,
            initialLoadSize = engine.budget.maxMetadataRows, maxSize = engine.budget.maxMetadataRows * 3,
        ),
        initialKey = anchor,
        remoteMediator = TimelineHistoryMediator(this, selection),
        pagingSourceFactory = { TimelineLedgerPagingSource(engine, selection).also(active::set) },
    ).flow
    return channelFlow {
        val pump = launch { pages.collect { send(it) } }
        // A superseded selection retires this generation without emitting, matching the prior contract.
        publication.distinctUntilChanged { old, new ->
            old.selection === new.selection && old.durableRevision == new.durableRevision
        }.collectIndexed { index, current ->
            if (current.selection !== selection) pump.cancel()
            else if (index > 0) active.get()?.invalidate()
        }
    }
}

/** Network history is requested only when Paging reaches the end of the local ledger. */
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
        if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
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
}
