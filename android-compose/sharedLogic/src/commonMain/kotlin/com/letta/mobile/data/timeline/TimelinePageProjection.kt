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
 *
 * A single-message run is claimed too: its page-built item carries the turn latency the run
 * disclosure needs, which a per-record projection cannot see (letta-mobile-qygvv.20).
 */
internal fun TimelinePageProjectionInput.aggregatePreparedRuns(
    prepared: List<TimelineSettledRecord>,
): List<TimelineSettledRecord> {
    val renderable = renderableRecords(prepared)
    if (renderable.size < 2) return prepared
    val grouped = buildChatRenderModel(
        messages = renderable.map { it.message }.withPromptOwnedRunIds(),
        mode = ChatDisplayMode.Interactive,
        activeAgentId = context.scope.agentId,
    ).renderItems
    val output = prepared.toMutableList()
    grouped.mapNotNull { it.runClaim() }
        .filter { envelope.containsComplete(it.runId) }
        .forEach { claim -> claimRun(output, claim, renderable) }
    return output
}

private fun TimelinePageProjectionInput.renderableRecords(
    prepared: List<TimelineSettledRecord>,
): List<IndexedRenderedRecord> = prepared.mapIndexedNotNull { index, record ->
    val presentation = record.preparedPresentation as? TimelineSettledPresentation.Render
        ?: return@mapIndexedNotNull null
    val message = timelineEventToUiMessage(presentation.event, context.ownAgentId)
        ?: return@mapIndexedNotNull null
    IndexedRenderedRecord(index, record, presentation, message)
}

/** A page-built run and the message ids it owns, in chat order. */
private data class RunClaim(val item: ChatRenderItem, val runId: String, val memberIds: List<String>)

private fun ChatRenderItem.runClaim(): RunClaim? = when (this) {
    is ChatRenderItem.RunBlock -> RunClaim(this, runId, messages.map { it.first.id })
        .takeIf { messages.size >= 2 }
    // Settled as the one-message block the per-record projection would have built, now carrying the
    // page-built message (its turn latency) under the run's key.
    is ChatRenderItem.Single -> stableRunId?.let { runId ->
        RunClaim(ChatRenderItem.RunBlock(runId, listOf(message to groupPosition), stableKey = key), runId, listOf(message.id))
    }
}

private fun TimelinePageProjectionInput.claimRun(
    output: MutableList<TimelineSettledRecord>,
    claim: RunClaim,
    renderable: List<IndexedRenderedRecord>,
) {
    val members = claim.memberIds.toSet()
    val sources = renderable.filter { it.message.id in members }
    if (sources.size != claim.memberIds.size || spansVisibleGap(sources)) return
    val owner = sources.minBy { it.index }
    output[owner.index] = owner.record.copy(
        preparedPresentation = TimelineSettledPresentation.Render(
            event = owner.presentation.event,
            item = claim.item,
            residentEvents = sources.map { it.residentEvent() },
        ),
    )
    sources.filter { it !== owner }.forEach { source ->
        output[source.index] = source.record.copy(preparedPresentation = TimelineSettledPresentation.Drop)
    }
}

/**
 * Skill instruction envelopes are hidden model context, not human turn boundaries. Returns are
 * folded into their canonical owners before projection; do not infer ownership for arbitrary
 * hidden rows (including orphan returns). A run's own stop_reason and usage frames close a step, not
 * the turn.
 */
private fun TimelinePageProjectionInput.spansVisibleGap(sources: List<IndexedRenderedRecord>): Boolean =
    sources.map { it.index }.sorted().zipWithNext().any { (left, right) ->
        (left + 1 until right).any { index -> !records[index].isRunInterior() }
    }

private fun TimelineProjectionRecord.isRunInterior(): Boolean =
    event?.isSyntheticSkillEnvelope() == true || isRunMetadata()

private fun IndexedRenderedRecord.residentEvent() = TimelineResidentEvent(
    identity = record.key.identity,
    revision = record.revision,
    otid = presentation.event.otid,
    serverId = presentation.event.serverId,
)

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
