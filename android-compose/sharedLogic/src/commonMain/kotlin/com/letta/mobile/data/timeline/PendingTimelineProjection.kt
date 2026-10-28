package com.letta.mobile.data.timeline

import androidx.paging.PagingData
import androidx.paging.TerminalSeparatorType
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Insert bounded local state between ledger rows without changing their server order. */
internal fun PagingData<CanonicalTimelinePresentation.Row>.insertPending(
    pending: List<CanonicalPendingLocalStore.Record>,
    scope: TimelineScope,
): PagingData<CanonicalTimelinePresentation.Row> {
    val optimistic = pending.optimisticRows(scope)
    if (optimistic.isEmpty()) return this
    return map { listOf(it) }.insertSeparators(TerminalSeparatorType.SOURCE_COMPLETE) { newer, older ->
        optimistic.filter { it.fitsPendingWindow(newer?.single(), older?.single()) }.takeIf { it.isNotEmpty() }
    }.flatMap { it }
}

private fun List<CanonicalPendingLocalStore.Record>.optimisticRows(scope: TimelineScope) =
    asReversed().sortedByDescending { parseTimestampEpochMillis(it.sentAt) ?: 0L }.map { record ->
        CanonicalTimelinePresentation.Row(
            TimelineMessageId("pending/${record.otid}"), 0, record.toRenderItem(scope.agentId), otid = record.otid,
        )
    }

private fun CanonicalTimelinePresentation.Row.fitsPendingWindow(
    newer: CanonicalTimelinePresentation.Row?,
    older: CanonicalTimelinePresentation.Row?,
): Boolean = isBetween(newer, older) && hasDistinctOtid(newer, older)

private fun CanonicalTimelinePresentation.Row.isBetween(
    newer: CanonicalTimelinePresentation.Row?,
    older: CanonicalTimelinePresentation.Row?,
): Boolean {
    val time = boundaryMillis()
    val upper = newer?.boundaryMillis()
    val lower = older?.boundaryMillis()
    return (upper == null || time < upper) && (lower == null || time >= lower)
}

private fun CanonicalTimelinePresentation.Row.hasDistinctOtid(
    newer: CanonicalTimelinePresentation.Row?,
    older: CanonicalTimelinePresentation.Row?,
): Boolean = otid != newer?.otid && otid != older?.otid

private fun CanonicalTimelinePresentation.Row.boundaryMillis(): Long =
    parseTimestampEpochMillis(item.boundaryTimestamp) ?: 0L

/** Position only local records by send time; the stream's established row order is authoritative. */
internal fun positionPendingInLive(active: List<ChatRenderItem>, pending: List<ChatRenderItem>): List<ChatRenderItem> {
    if (pending.isEmpty()) return active
    val ordered = pending.sortedByDescending { parseTimestampEpochMillis(it.boundaryTimestamp) ?: 0L }
    return buildList {
        var next = 0
        active.forEach { item ->
            // A stream without a usable clock remains ahead of older local records. Never sort
            // active rows together: even valid server dates can regress during a turn.
            val boundary = parseTimestampEpochMillis(item.boundaryTimestamp) ?: Long.MAX_VALUE
            while (next < ordered.size && (parseTimestampEpochMillis(ordered[next].boundaryTimestamp) ?: 0L) > boundary) {
                add(ordered[next++])
            }
            add(item)
        }
        addAll(ordered.drop(next))
    }
}
