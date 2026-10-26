package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Immutable
import androidx.paging.ItemSnapshotList
import androidx.paging.compose.LazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A cached lazy slot reads the same immutable page snapshot as its key and content type. */
internal fun <T : Any> pagedSnapshotItem(snapshot: ItemSnapshotList<T>, index: Int): T? =
    snapshot.getOrNull(index)

/**
 * Send load hints after composition, from a LaunchedEffect, never from a cached slot's render.
 * A Compose snapshot can still contain five items after Paging's mutable presenter holds four:
 * even checking [LazyPagingItems.itemCount] during composition does not make `get(4)` safe.
 *
 * On Main, with no suspension between validation and access, Paging cannot replace its presenter
 * under this hint. Reject an obsolete snapshot even at the same size: its index belongs to a
 * different item key. The next composition sends hints for the replacement's visible slots.
 */
internal suspend fun <T : Any> LazyPagingItems<T>.hintPagedSnapshot(
    snapshot: ItemSnapshotList<T>,
    index: Int,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (itemSnapshotList !== snapshot || index !in snapshot.indices) {
        false
    } else {
        get(index)
        true
    }
}

/** The two sources [PagedRows] is read from, compared by value to key effects on either. */
@Immutable
internal data class PagedRowsIdentity(
    val live: List<ChatRenderItem>,
    val settled: ItemSnapshotList<CanonicalTimelinePresentation.Row>,
)

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
    val identity: PagedRowsIdentity,
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
        pagedSnapshotItem(identity.settled, index - leading - liveCount)

    fun indexOf(rowIdentity: String): Int? =
        canonicalRowIndex(assembly.live, identity.settled.items, rowIdentity)?.let {
            it + leading + if (it >= liveCount) identity.settled.placeholdersBefore else 0
        }
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
