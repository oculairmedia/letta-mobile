package com.letta.mobile.data.timeline

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

/** Newest-first adapter. Paging alone owns load state, retry, resident caching and page dropping. */
class TimelineLedgerPagingSource(
    private val engine: CanonicalTimelineEngine,
    private val selection: TimelineEngineSelection,
    private val ownAgentId: String? = selection.scope.agentId,
    private val settledProjectionAdapter: TimelineSettledProjectionAdapter = DefaultTimelineSettledProjectionAdapter,
) : PagingSource<TimelinePageKey, TimelineSettledRecord>() {
    /** The durable revision this source reads; any later one makes it obsolete. */
    internal val revision = engine.publication.value.durableRevision

    /**
     * Invalidates this source only when it was built for a revision before [durableRevision].
     *
     * A source that loads after a revision lands returns [LoadResult.Invalid] and Paging replaces it
     * itself, so by the time the revision collector reaches that revision the active source may
     * already be its replacement, built at that revision. Invalidating that one too spent two
     * generations on one revision and redrew the list twice (CI: 9 generations for 7 revisions).
     */
    internal fun invalidateIfBehind(durableRevision: Long): Boolean {
        if (revision >= durableRevision) return false
        invalidate()
        return true
    }

    override fun getRefreshKey(state: PagingState<TimelinePageKey, TimelineSettledRecord>): TimelinePageKey? =
        state.anchorPosition?.let { state.closestItemToPosition(it)?.key } ?: selection.anchor

    private fun isObsolete(): Boolean {
        if (invalid) return true
        val publication = engine.publication.value
        if (publication.selection !== selection) return true
        return publication.durableRevision != revision
    }

    override suspend fun load(params: LoadParams<TimelinePageKey>): LoadResult<TimelinePageKey, TimelineSettledRecord> {
        if (isObsolete()) return LoadResult.Invalid()
        return try {
            val position = when (params) {
                is LoadParams.Refresh -> params.key?.let(TimelineReadPosition::Around) ?: TimelineReadPosition.Tail
                is LoadParams.Append -> TimelineReadPosition.Before(params.key)
                is LoadParams.Prepend -> TimelineReadPosition.After(params.key)
            }
            val page = engine.preparePage(selection, position, params.loadSize, ownAgentId, settledProjectionAdapter)
            if (isObsolete()) return LoadResult.Invalid()
            LoadResult.Page(
                data = page.records.reversed(),
                prevKey = page.metadata.newer,
                nextKey = page.metadata.older,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isObsolete()) LoadResult.Invalid() else LoadResult.Error(failure)
        }
    }
}
