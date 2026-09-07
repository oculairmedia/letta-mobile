package com.letta.mobile.data.timeline

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Each durable publication cancels obsolete loaders and starts a selection-anchored generation. */
@OptIn(ExperimentalCoroutinesApi::class)
fun CanonicalTimelineSession.paging(selection: TimelineEngineSelection): Flow<PagingData<TimelineSettledRecord>> =
    publication.distinctUntilChanged { old, new ->
        old.selection === new.selection && old.durableRevision == new.durableRevision
    }.flatMapLatest { current ->
        if (current.selection !== selection) emptyFlow() else Pager(
            config = PagingConfig(pageSize = engine.budget.maxMetadataRows, enablePlaceholders = false,
                initialLoadSize = engine.budget.maxMetadataRows, maxSize = engine.budget.maxMetadataRows * 3),
            initialKey = selection.anchor,
            pagingSourceFactory = { TimelineLedgerPagingSource(engine, selection) },
        ).flow
    }
