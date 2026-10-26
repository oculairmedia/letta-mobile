package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.CombinedLoadStates
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Compose binding to Paging's presenter with an identity for every presented page event.
 * Paging 3.5 installs the refresh's hint receiver before [presentPagingDataEvent]. Its snapshot
 * alone is insufficient: equal-content refreshes compare equal but need fresh viewport hints.
 */
internal class PagedTimelineItems<T : Any>(cached: PagingData<T>? = null) :
    PagingDataPresenter<T>(mainContext = Dispatchers.Main.immediate, cachedPagingData = cached) {
    var presentedSnapshot by mutableStateOf(0L to snapshot())
        private set

    val itemSnapshotList: ItemSnapshotList<T> get() = presentedSnapshot.second

    var loadState by mutableStateOf(
        loadStateFlow.value ?: LoadStates(
            LoadState.Loading, LoadState.NotLoading(false), LoadState.NotLoading(false),
        ).let { CombinedLoadStates(it.refresh, it.prepend, it.append, it) },
    )
        private set

    init {
        addLoadStateListener { loadState = it }
    }

    override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) {
        presentedSnapshot = (presentedSnapshot.first + 1L) to snapshot()
    }
}

/** Collect only while mounted; preserve Paging's cached initial presentation and latest generation. */
@Composable
internal fun <T : Any> Flow<PagingData<T>>.collectAsPagedTimelineItems(): PagedTimelineItems<T> {
    val items = remember(this) { PagedTimelineItems((this as? SharedFlow)?.replayCache?.firstOrNull()) }
    LaunchedEffect(items) { collectLatest { items.collectFrom(it) } }
    return items
}

/** A cached lazy slot reads the same immutable page snapshot as its key and content type. */
internal fun <T : Any> pagedSnapshotItem(snapshot: ItemSnapshotList<T>, index: Int): T? =
    snapshot.getOrNull(index)

/**
 * Send load hints after composition, from a LaunchedEffect, never from a cached slot's render.
 * A Compose snapshot can still contain five items after Paging's mutable presenter holds four:
 * even checking the Compose item count during composition does not make `get(4)` safe.
 *
 * On Main, with no suspension between validation and access, Paging cannot replace its presenter
 * under this hint. Reject an obsolete snapshot even at the same size: its index belongs to a
 * different item key. The next composition sends hints for the replacement's visible slots.
 */
internal suspend fun <T : Any> PagedTimelineItems<T>.hintPagedSnapshot(
    snapshot: ItemSnapshotList<T>,
    index: Int,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (itemSnapshotList !== snapshot || index !in snapshot.indices || index !in 0 until size) {
        false
    } else {
        get(index)
        true
    }
}

/**
 * The thinking row, the live overlay and the settled pages as one index space, read from one
 * snapshot of each source (TimelineRowAssembly). Content, neighbours, keys and types all read
 * that captured snapshot, even while Paging replaces its presenter and lazy slots recompose.
 */
@Immutable
internal class PagedRows(
    val leading: Int,
    val assembly: TimelineRowAssembly,
    /** Changes whenever either source does, so effects keyed on it re-run exactly then. */
    val identity: Pair<List<ChatRenderItem>, ItemSnapshotList<CanonicalTimelinePresentation.Row>>,
    /** Distinguishes presented generations even when every row and placeholder compares equal. */
    val presented: Long = 0L,
) {
    val size: Int get() = leading + assembly.size
    val liveCount: Int get() = assembly.liveCount

    fun key(index: Int): String = assembly.key(index - leading) ?: "settled-slot-${index - leading}"

    fun contentType(index: Int): String? = itemAt(index)?.let { it::class.simpleName }

    fun itemAt(index: Int): ChatRenderItem? {
        val i = index - leading
        if (i < 0) return null
        return assembly.live.getOrNull(i) ?: settledRowAt(index)?.item
    }

    fun settledRowAt(index: Int): CanonicalTimelinePresentation.Row? =
        pagedSnapshotItem(identity.second, index - leading - liveCount)

    fun indexOf(rowIdentity: String): Int? =
        canonicalRowIndex(assembly.live, identity.second.items, rowIdentity)?.let {
            it + leading + if (it >= liveCount) identity.second.placeholdersBefore else 0
        }
}

/** Capture one presentation for keys, content and hints, including equal-content replacements. */
@Composable
internal fun rememberPagedRows(
    live: List<ChatRenderItem>,
    settled: PagedTimelineItems<CanonicalTimelinePresentation.Row>,
    leading: Int,
): PagedRows {
    val presented = settled.presentedSnapshot
    val snapshot = presented.second
    return remember(live, presented.first, leading) {
        val assembly = TimelineRowAssembly.assemble(live, snapshot.map { it?.item?.key })
        PagedRows(leading, assembly, identity = live to snapshot, presented = presented.first)
    }
}

/** The mounted slot owns its hint effect; row rendering always reads its captured presentation. */
@Composable
internal fun PagedSlot(
    rows: PagedRows,
    settled: PagedTimelineItems<CanonicalTimelinePresentation.Row>,
    index: Int,
    content: @Composable (ChatRenderItem?, CanonicalTimelinePresentation.Row?) -> Unit,
) {
    val offset = index - rows.leading
    val live = rows.assembly.live.getOrNull(offset)
    val pagingIndex = offset - rows.liveCount
    if (live == null) {
        LaunchedEffect(settled, rows.presented, pagingIndex) {
            settled.hintPagedSnapshot(rows.identity.second, pagingIndex)
        }
    }
    val row = if (live == null) rows.settledRowAt(index) else null
    content(live ?: row?.item, row)
}

/** Resident rows only: an identity not paged in yet has no index (desktop canonicalRowIndex). */
internal fun canonicalRowIndex(
    live: List<ChatRenderItem>,
    settled: List<CanonicalTimelinePresentation.Row>,
    identity: String,
): Int? {
    val inSettled = settled.indexOfFirst { it.identity.value == identity }
    if (inSettled >= 0) return live.size + inSettled
    return live.indexOfFirst { it.containsMessageId(identity) }.takeIf { it >= 0 }
}
