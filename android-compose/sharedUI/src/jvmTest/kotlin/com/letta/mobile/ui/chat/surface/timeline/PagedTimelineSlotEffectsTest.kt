package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real compositions mount production slots and their effects; no test sends a viewport hint. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedTimelineSlotEffectsTest {
    @Test
    fun identicalRefreshRearmsTheSameMountedSlotsAndStopsAtTheLastPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val timeline = MountedTimeline(this)
        try {
            timeline.pump()
            assertEquals(listOf("0:refresh", "0:append"), timeline.loads)
            val original = timeline.items.presentedSnapshot
            assertEquals(listOf("a", "b", null, null), original.second.map { it?.identity?.value })
            val mounts = timeline.mounts.toMap()
            val keys = timeline.visibleKeys.toList()

            timeline.sources.single().invalidate()
            timeline.pump()
            assertEquals(listOf("0:refresh", "0:append", "1:refresh"), timeline.loads)
            assertTrue(0 in timeline.cancelledAppends, "invalidation must cancel A's suspended append")
            timeline.refreshB.complete(Unit)
            timeline.pump()

            assertEquals(listOf("0:refresh", "0:append", "1:refresh", "1:append"), timeline.loads)
            assertEquals(original.second, timeline.items.itemSnapshotList)
            assertNotSame(original.first, timeline.items.presentedSnapshot.first)
            assertNotSame(original.second, timeline.items.itemSnapshotList)
            assertEquals(keys, timeline.visibleKeys)
            assertEquals(mounts, timeline.mounts, "the same keyed slots must stay mounted; no scroll or rebind")
            timeline.appendB.complete(Unit)
            timeline.pump()
            assertEquals(listOf("a", "b", "c", "d"), timeline.items.itemSnapshotList.map { it?.identity?.value })
            assertTrue(timeline.items.loadState.append.endOfPaginationReached)
            val settled = timeline.items.presentedSnapshot
            repeat(3) { timeline.pump() }
            assertSame(settled, timeline.items.presentedSnapshot)
            assertEquals(4, timeline.loads.size, "finished paging must not spin or refresh itself")
            assertEquals(2, timeline.sources.size)

            timeline.close()
            timeline.sources.last().invalidate()
            timeline.pump()
            assertEquals(4, timeline.loads.size, "a disposed composition must stop collecting generations")
            assertEquals(2, timeline.sources.size)
        } finally {
            timeline.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun disposingVisibleSlotsStopsHintsWhileThePresenterStillCollects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val timeline = MountedTimeline(this)
        try {
            timeline.pump()
            assertEquals(listOf("0:refresh", "0:append"), timeline.loads)
            timeline.showSlots = false
            timeline.pump()
            assertEquals(setOf(1, 2), timeline.disposedSlots)

            timeline.sources.single().invalidate()
            timeline.pump()
            timeline.refreshB.complete(Unit)
            timeline.pump()
            assertEquals(listOf("a", "b", null, null), timeline.items.itemSnapshotList.map { it?.identity?.value })
            repeat(3) { timeline.pump() }
            assertEquals(listOf("0:refresh", "0:append", "1:refresh"), timeline.loads)
            assertFalse(timeline.items.loadState.append.endOfPaginationReached)
            assertTrue(0 in timeline.cancelledAppends)
        } finally {
            timeline.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun disposalCancelsASlotHintBeforeItsCoroutineRuns() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val timeline = MountedTimeline(this, initiallyMounted = false)
        try {
            timeline.pump()
            assertEquals(listOf("0:refresh"), timeline.loads)
            assertTrue(timeline.mountThenDisposeSlot())
            timeline.pump()
            assertEquals(listOf("0:refresh"), timeline.loads, "a cancelled slot must not start append")
        } finally {
            timeline.close()
            Dispatchers.resetMain()
        }
    }

    /** A real Recomposer with fixed visible indexes, without a native window or layout/scroll noise. */
    private class MountedTimeline(private val scope: TestScope, initiallyMounted: Boolean = true) {
        val sources = mutableListOf<PagingSource<Int, CanonicalTimelinePresentation.Row>>()
        val loads = mutableListOf<String>()
        val cancelledAppends = mutableSetOf<Int>()
        val refreshB = CompletableDeferred<Unit>()
        val appendB = CompletableDeferred<Unit>()
        val mounts = mutableMapOf<Int, Int>()
        val disposedSlots = mutableSetOf<Int>()
        var visibleKeys = emptyList<String>()
            private set
        var showSlots by mutableStateOf(initiallyMounted)
        lateinit var items: PagedTimelineItems<CanonicalTimelinePresentation.Row>
            private set

        private val data = listOf("a", "b", "c", "d").map { id ->
            CanonicalTimelinePresentation.Row(
                identity = TimelineMessageId(id),
                revision = 1,
                item = ChatRenderItem.Single(
                    UiMessage(id = id, role = "assistant", content = id, timestamp = "2026-10-26T10:00:00Z"),
                    GroupPosition.None,
                ),
            )
        }
        private val pages = Pager(
            PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 1, enablePlaceholders = true),
        ) { source() }.flow
        private val frames = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frames)
        private val composition = newComposition()
        private var frameTime = 0L

        private fun newComposition() = Composition(
            object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            },
            recomposer,
        )
        init {
            scope.backgroundScope.launch(frames) { recomposer.runRecomposeAndApplyChanges() }
            composition.setContent {
                val settled = pages.collectAsPagedTimelineItems()
                SideEffect { items = settled }
                if (showSlots) {
                    val rows = rememberPagedRows(emptyList(), settled, leading = 0)
                    val visible = listOf(1, 2).filter { it < rows.size }
                    SideEffect { visibleKeys = visible.map(rows::key) }
                    for (index in visible) {
                        key(rows.key(index)) {
                            PagedSlot(rows, settled, index) { _, _ ->
                                DisposableEffect(Unit) {
                                    mounts[index] = mounts.getOrElse(index) { 0 } + 1
                                    onDispose { disposedSlots += index }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun pump() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                frames.sendFrame(++frameTime)
                scope.runCurrent()
            }
        }

        fun close() {
            composition.dispose()
            recomposer.cancel()
        }

        fun mountThenDisposeSlot(): Boolean {
            var disposed = false
            val slot = newComposition()
            slot.setContent {
                PagedSlot(rememberPagedRows(emptyList(), items, leading = 0), items, index = 2) { _, _ ->
                    DisposableEffect(Unit) { onDispose { disposed = true } }
                }
            }
            slot.dispose()
            return disposed
        }

        private fun source(): PagingSource<Int, CanonicalTimelinePresentation.Row> {
            val generation = sources.size
            check(generation < 2) { "unexpected refresh loop" }
            return object : PagingSource<Int, CanonicalTimelinePresentation.Row>() {
                override fun getRefreshKey(state: PagingState<Int, CanonicalTimelinePresentation.Row>): Int = 0

                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CanonicalTimelinePresentation.Row> {
                    val start = params.key ?: 0
                    loads += "$generation:${if (start == 0) "refresh" else "append"}"
                    if (start == 0 && generation == 1) refreshB.await()
                    if (start > 0) {
                        if (generation == 0) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelledAppends += generation
                            }
                        }
                        appendB.await()
                    }
                    val end = (start + params.loadSize).coerceAtMost(data.size)
                    return LoadResult.Page(
                        data = data.subList(start, end),
                        prevKey = null,
                        nextKey = end.takeIf { it < data.size },
                        itemsBefore = start,
                        itemsAfter = data.size - end,
                    )
                }
            }.also { sources += it }
        }
    }
}
