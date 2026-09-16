package com.letta.mobile.data.timeline

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Revision publication replaces the Pager; token updates must never publish durable revisions. */
@OptIn(ExperimentalCoroutinesApi::class)
fun CanonicalTimelineEngine.settledPages(selection: TimelineEngineSelection): Flow<PagingData<TimelineSettledRecord>> =
    publication.flatMapLatest { state ->
        if (state.selection !== selection) emptyFlow() else Pager(
            config = PagingConfig(
                pageSize = budget.maxMetadataRows,
                initialLoadSize = budget.maxMetadataRows,
                enablePlaceholders = false,
                maxSize = budget.maxMetadataRows * 3,
            ),
            initialKey = selection.anchor,
            pagingSourceFactory = { TimelineLedgerPagingSource(this, selection) },
        ).flow
    }
