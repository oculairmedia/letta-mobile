package com.letta.mobile.data.timeline

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

/** Newest-first adapter. Paging alone owns load state, retry, resident caching and page dropping. */
class TimelineLedgerPagingSource(
    private val engine: CanonicalTimelineEngine,
    private val selection: TimelineEngineSelection,
) : PagingSource<TimelinePageKey, TimelineSettledRecord>() {
    private val revision = engine.publication.value.durableRevision
    override fun getRefreshKey(state: PagingState<TimelinePageKey, TimelineSettledRecord>): TimelinePageKey? =
        state.anchorPosition?.let { state.closestItemToPosition(it)?.key } ?: selection.anchor

    override suspend fun load(params: LoadParams<TimelinePageKey>): LoadResult<TimelinePageKey, TimelineSettledRecord> {
        if (invalid || engine.publication.value.selection !== selection) return LoadResult.Invalid()
        if (engine.publication.value.durableRevision != revision) return LoadResult.Invalid()
        return try {
            val position = when (params) {
                is LoadParams.Refresh -> params.key?.let(TimelineReadPosition::Around) ?: TimelineReadPosition.Tail
                is LoadParams.Append -> TimelineReadPosition.Before(params.key)
                is LoadParams.Prepend -> TimelineReadPosition.After(params.key)
            }
            val page = engine.load(selection, position, params.loadSize)
            if (invalid || engine.publication.value.selection !== selection ||
                engine.publication.value.durableRevision != revision
            ) return LoadResult.Invalid()
            LoadResult.Page(
                data = page.metadata.rows.zip(page.bodies) { metadata, body ->
                    TimelineSettledRecord(metadata.key, metadata.contentType, body, page.metadata.revision, metadata.body)
                }.reversed(),
                prevKey = page.metadata.newer,
                nextKey = page.metadata.older,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (invalid || engine.publication.value.selection !== selection ||
                engine.publication.value.durableRevision != revision
            ) LoadResult.Invalid() else LoadResult.Error(failure)
        }
    }
}
