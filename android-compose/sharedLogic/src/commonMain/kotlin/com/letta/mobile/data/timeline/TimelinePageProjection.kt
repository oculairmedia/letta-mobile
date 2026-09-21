package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
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

/**
 * Groups renderable residents before Paging creates rows. Per-record projection is still retained
 * on every source record for deferred bodies and settlement provenance; only the first record in a
 * render block owns the combined item and the remaining records are dropped from presentation.
 */
internal fun TimelinePageProjectionInput.aggregatePreparedRuns(
    prepared: List<TimelineSettledRecord>,
): List<TimelineSettledRecord> {
    val renderable = prepared.mapIndexedNotNull { index, record ->
        val presentation = record.preparedPresentation as? TimelineSettledPresentation.Render
            ?: return@mapIndexedNotNull null
        val message = timelineEventToUiMessage(presentation.event, context.ownAgentId)
            ?: return@mapIndexedNotNull null
        IndexedRenderedRecord(index, record, presentation, message)
    }
    if (renderable.size < 2) return prepared

    val grouped = buildChatRenderModel(
        messages = renderable.map { it.message },
        mode = ChatDisplayMode.Interactive,
        activeAgentId = context.scope.agentId,
    ).renderItems
    if (grouped.size == renderable.size) return prepared

    val output = prepared.toMutableList()
    grouped.forEach { item ->
        if (item !is ChatRenderItem.RunBlock || item.messages.size < 2) return@forEach
        if (!envelope.containsComplete(item.runId)) return@forEach
        val members = item.messages.map { it.first.id }.toSet()
        val sources = renderable.filter { it.message.id in members }
        if (sources.size != item.messages.size) return@forEach
        val sourceIndexes = sources.map { it.index }.sorted()
        if (sourceIndexes.zipWithNext().any { (left, right) -> right != left + 1 }) return@forEach
        val owner = sources.minBy { it.index }
        output[owner.index] = owner.record.copy(
            preparedPresentation = TimelineSettledPresentation.Render(
                event = owner.presentation.event,
                item = item,
                residentEvents = sources.map { source ->
                    TimelineResidentEvent(
                        identity = source.record.key.identity,
                        revision = source.record.revision,
                        otid = source.presentation.event.otid,
                        serverId = source.presentation.event.serverId,
                    )
                },
            ),
        )
        sources.drop(1).forEach { source ->
            output[source.index] = source.record.copy(preparedPresentation = TimelineSettledPresentation.Drop)
        }
    }
    return output
}

private fun TimelineRunEnvelope.containsComplete(runId: String): Boolean =
    older != TimelineRunBoundary.Continues(runId) && newer != TimelineRunBoundary.Continues(runId)

private data class IndexedRenderedRecord(
    val index: Int,
    val record: TimelineSettledRecord,
    val presentation: TimelineSettledPresentation.Render,
    val message: com.letta.mobile.data.model.UiMessage,
)

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
