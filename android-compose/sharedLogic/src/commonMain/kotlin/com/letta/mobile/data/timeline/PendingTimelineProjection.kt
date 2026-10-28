package com.letta.mobile.data.timeline

import androidx.paging.PagingData
import androidx.paging.TerminalSeparatorType
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Insert bounded local state between ledger rows without changing their server order. */
internal fun PagingData<CanonicalTimelinePresentation.Row>.insertPending(
    pending: List<CanonicalPendingLocalStore.Record>,
    scope: TimelineScope,
): PagingData<CanonicalTimelinePresentation.Row> {
    val optimistic = pending.asReversed().sortedByDescending { parseTimestampEpochMillis(it.sentAt) ?: 0L }.map { record ->
        CanonicalTimelinePresentation.Row(
            TimelineMessageId("pending/${record.otid}"), 0, record.toRenderItem(scope.agentId), otid = record.otid,
        )
    }
    if (optimistic.isEmpty()) return this
    return map { listOf(it) }.insertSeparators(TerminalSeparatorType.SOURCE_COMPLETE) { newer, older ->
        val upper = newer?.single()?.let { parseTimestampEpochMillis(it.item.boundaryTimestamp) ?: 0L }
        val lower = older?.single()?.let { parseTimestampEpochMillis(it.item.boundaryTimestamp) ?: 0L }
        optimistic.filter { row ->
            val time = parseTimestampEpochMillis(row.item.boundaryTimestamp) ?: 0L
            (upper == null || time < upper) && (lower == null || time >= lower) &&
                row.otid != newer?.single()?.otid && row.otid != older?.single()?.otid
        }.takeIf { it.isNotEmpty() }
    }.flatMap { it }
}
