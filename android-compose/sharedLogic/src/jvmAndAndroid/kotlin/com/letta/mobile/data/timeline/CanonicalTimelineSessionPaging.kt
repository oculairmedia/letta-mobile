package com.letta.mobile.data.timeline

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Each durable publication cancels obsolete loaders and starts a selection-anchored generation. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
fun CanonicalTimelineSession.paging(selection: TimelineEngineSelection): Flow<PagingData<TimelineSettledRecord>> =
    publication.distinctUntilChanged { old, new ->
        old.selection === new.selection && old.durableRevision == new.durableRevision
    }.flatMapLatest { current ->
        if (current.selection !== selection) emptyFlow() else Pager(
            config = PagingConfig(pageSize = engine.budget.maxMetadataRows, enablePlaceholders = false,
                initialLoadSize = engine.budget.maxMetadataRows, maxSize = engine.budget.maxMetadataRows * 3),
            initialKey = selection.anchor,
            remoteMediator = TimelineHistoryMediator(this, selection),
            pagingSourceFactory = { TimelineLedgerPagingSource(engine, selection) },
        ).flow
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
