package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Paging 3.5 presenter events, with a retained Compose snapshot just like a cached lazy slot. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedTimelineAccessTest {
    @Test
    fun staleComposeSnapshotPassesCountGuardButPresenterHasAlreadyShrunk() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (items, presenter) = pagingItems()
            presenter.collectFrom(PagingData.from(List(5) { "old-$it" }))
            val composition = Snapshot.takeSnapshot()
            try {
                presenter.collectFrom(PagingData.from(List(4) { "new-$it" }))
                composition.enter {
                    assertEquals(5, items.itemCount)
                    val failure = assertFailsWith<IndexOutOfBoundsException> {
                        if (4 < items.itemCount) items[4]
                    }
                    assertTrue(failure.message.orEmpty().contains("Index: 4, Size: 4"))
                }
            } finally {
                composition.dispose()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cachedFifthSlotSurvivesFiveToFourAndCurrentScrollingResumes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (items, presenter) = timelineItems()
            presenter.collectFrom(PagingData.from(List(5) { "old-$it" }))
            val cached = items.itemSnapshotList
            presenter.collectFrom(PagingData.from(List(4) { "new-$it" }))
            assertEquals("old-4", pagedSnapshotItem(cached, 4))
            assertFalse(items.hintPagedSnapshot(cached, 4))
            val current = items.itemSnapshotList
            assertEquals("new-3", pagedSnapshotItem(current, 3))
            assertTrue(items.hintPagedSnapshot(current, 3))
            assertFalse(items.hintPagedSnapshot(current, 4))
            assertFalse(items.hintPagedSnapshot(current, -1))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cachedOnlySlotSurvivesOneToZeroThenRepopulation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (items, presenter) = timelineItems()
            presenter.collectFrom(PagingData.from(listOf("only")))
            val cached = items.itemSnapshotList
            presenter.collectFrom(PagingData.empty())
            assertEquals("only", pagedSnapshotItem(cached, 0))
            assertFalse(items.hintPagedSnapshot(cached, 0))
            assertNull(pagedSnapshotItem(items.itemSnapshotList, 0))
            presenter.collectFrom(PagingData.from(listOf("returned")))
            assertTrue(items.hintPagedSnapshot(items.itemSnapshotList, 0))
            assertEquals("returned", pagedSnapshotItem(items.itemSnapshotList, 0))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sameSizeReplacementCannotHintUnderTheOldItemKey() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (items, presenter) = timelineItems()
            presenter.collectFrom(PagingData.from(List(5) { "old-key-$it" }))
            val cached = items.itemSnapshotList
            presenter.collectFrom(PagingData.from(List(5) { "replacement-key-$it" }))
            assertEquals(cached.size, items.itemSnapshotList.size)
            assertEquals("old-key-4", pagedSnapshotItem(cached, 4))
            assertFalse(items.hintPagedSnapshot(cached, 4))
            assertEquals("replacement-key-4", pagedSnapshotItem(items.itemSnapshotList, 4))
            assertTrue(items.hintPagedSnapshot(items.itemSnapshotList, 4))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun currentPlaceholderHintLoadsAnotherPageAfterAStaleSlotWasSkipped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (items, presenter) = timelineItems()
            presenter.collectFrom(PagingData.from(List(5) { "discarded-$it" }))
            val cached = items.itemSnapshotList
            val loads = mutableListOf<Int>()
            val pager = Pager(PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 1)) {
                object : PagingSource<Int, String>() {
                    override fun getRefreshKey(state: PagingState<Int, String>): Int? = state.anchorPosition

                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                        val start = params.key ?: 0
                        val end = (start + params.loadSize).coerceAtMost(6)
                        loads += start
                        return LoadResult.Page(
                            data = (start until end).map { "paged-$it" },
                            prevKey = null,
                            nextKey = end.takeIf { it < 6 },
                            itemsBefore = start,
                            itemsAfter = 6 - end,
                        )
                    }
                }
            }
            backgroundScope.launch { presenter.collectFrom(pager.flow.first()) }
            runCurrent()
            val loading = items.itemSnapshotList
            assertEquals(listOf(0), loads)
            assertEquals(6, loading.size)
            assertNull(pagedSnapshotItem(loading, 2))
            assertFalse(items.hintPagedSnapshot(cached, 4))
            assertEquals(listOf(0), loads)
            assertTrue(items.hintPagedSnapshot(loading, 2))
            runCurrent()
            assertTrue(2 in loads, "the placeholder access must still drive Paging: $loads")
            assertEquals("paged-2", pagedSnapshotItem(items.itemSnapshotList, 2))
            assertNull(pagedSnapshotItem(loading, 2))
            assertFalse(items.hintPagedSnapshot(loading, 2))
            assertTrue(items.hintPagedSnapshot(items.itemSnapshotList, 4))
            runCurrent()
            assertTrue(4 in loads, "continued scrolling must load the final page: $loads")
            assertEquals("paged-5", pagedSnapshotItem(items.itemSnapshotList, 5))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun timelineItems(): Pair<PagedTimelineItems<String>, PagingDataPresenter<String>> {
        val items = PagedTimelineItems<String>()
        return items to items
    }

    /** Access the pinned library's collector without a window or a substitute presenter. */
    @Suppress("UNCHECKED_CAST")
    private fun pagingItems(): Pair<LazyPagingItems<String>, PagingDataPresenter<String>> {
        val constructor = LazyPagingItems::class.java.getDeclaredConstructor(Flow::class.java)
        constructor.isAccessible = true
        val items = constructor.newInstance(emptyFlow<PagingData<String>>()) as LazyPagingItems<String>
        val field = LazyPagingItems::class.java.getDeclaredField("pagingDataPresenter")
        field.isAccessible = true
        return items to field.get(items) as PagingDataPresenter<String>
    }
}
