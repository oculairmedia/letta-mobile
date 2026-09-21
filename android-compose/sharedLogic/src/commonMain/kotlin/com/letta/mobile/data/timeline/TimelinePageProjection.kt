package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Canonical ownership, shared by settled and live projection; never inferred from message content. */
data class TimelineProjectionContext(val scope: TimelineScope, val ownAgentId: String?)

sealed interface TimelineRunBoundary {
    data class Continues(val runId: String) : TimelineRunBoundary {
        init { require(runId.isNotBlank()) }
    }
    data object Ends : TimelineRunBoundary
    /** Not a split or a join. Consumers must retain partial runs until evidence is available. */
    data object Unknown : TimelineRunBoundary
}

/** Edges refer to the first/last physical resident record, including filtered records. */
data class TimelineRunEnvelope(val older: TimelineRunBoundary, val newer: TimelineRunBoundary)

/** Decoded evidence survives filtering so hidden rows cannot silently join unrelated runs. */
data class TimelineProjectionRecord(
    val record: TimelineSettledRecord,
    val event: TimelineEvent.Confirmed?,
    val excluded: Boolean,
)

data class TimelinePageProjectionInput(
    val context: TimelineProjectionContext,
    val records: List<TimelineProjectionRecord>,
    val envelope: TimelineRunEnvelope,
)

/** No final render rows are constructed until the complete bounded input has been prepared. */
internal fun TimelinePageProjectionInput.project(adapter: TimelineSettledProjectionAdapter): List<TimelineSettledRecord> =
    records.map { input ->
        val record = input.record
        val presentation = when {
            input.excluded -> TimelineSettledPresentation.Drop
            input.event == null -> TimelineSettledPresentation.Defer
            else -> adapter.project(record, input.event, context.ownAgentId)
                ?.let { TimelineSettledPresentation.Render(input.event, it) }
                ?: TimelineSettledPresentation.Drop
        }
        record.copy(preparedPresentation = presentation)
    }

/** At most two metadata queries (one row each), two body reads, and 32 KiB additional bytes.
 * Body bytes also consume the remaining configured page budget; no scanning past an unknown edge.
 */
internal suspend fun TimelineStoreReader.runEnvelope(
    page: TimelineMetadataPage,
    records: List<TimelineProjectionRecord>,
    remainingBodyBytes: Long,
    adapter: TimelineSettledProjectionAdapter,
): TimelineRunEnvelope {
    var remaining = remainingBodyBytes
    suspend fun boundary(key: TimelinePageKey?, resident: TimelineProjectionRecord?, older: Boolean): TimelineRunBoundary {
        if (key == null) return TimelineRunBoundary.Ends
        if (resident?.event == null) return TimelineRunBoundary.Unknown
        val position = if (older) TimelineReadPosition.Before(resident.record.key)
            else TimelineReadPosition.After(resident.record.key)
        val adjacent = metadata(position, 1)
        check(adjacent.rows.size <= 1 && adjacent.revision == page.revision) { "Invalid boundary snapshot" }
        val row = adjacent.rows.singleOrNull() ?: return TimelineRunBoundary.Unknown
        check(if (older) row.key < resident.record.key else row.key > resident.record.key) { "Invalid boundary key" }
        if (row.contentType != TIMELINE_EVENT_CONTENT_TYPE ||
            row.body.encodedBytes > minOf(remaining, MAX_BOUNDARY_BODY_BYTES)
        ) return TimelineRunBoundary.Unknown
        val size = row.body.encodedBytes.toInt()
        val bytes = if (size == 0) byteArrayOf() else body(row.body, 0, size)
        check(bytes.size == size) { "Incomplete boundary body" }
        remaining -= size
        // Never catch decode failures and reinterpret corrupt canonical evidence as Unknown.
        val event = adapter.decode(TimelineSettledRecord(row.key, row.contentType, bytes, page.revision, row.body))
        val residentEvent = resident.event
        val run = residentEvent.runId?.takeIf { it.isNotBlank() }
        return if (run != null && run == event.runId) TimelineRunBoundary.Continues(run)
        else TimelineRunBoundary.Ends
    }
    return TimelineRunEnvelope(
        boundary(page.older, records.firstOrNull(), older = true),
        boundary(page.newer, records.lastOrNull(), older = false),
    )
}

internal const val MAX_BOUNDARY_BODY_BYTES: Long = 16L * 1024
