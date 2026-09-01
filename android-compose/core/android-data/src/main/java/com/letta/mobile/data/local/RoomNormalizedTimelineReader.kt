package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import java.nio.charset.StandardCharsets

internal sealed interface NormalizedTimelineRead {
    data class Valid(val envelope: StoredTimelineEnvelope) : NormalizedTimelineRead
    data class Invalid(val failure: SnapshotReadFailure) : NormalizedTimelineRead
}

internal const val NORMALIZED_LAYOUT_VERSION = 1

internal class RoomNormalizedTimelineReader {
    fun read(
        scope: TimelineScope,
        head: NormalizedTimelineSnapshotHeadEntity,
        rows: List<NormalizedTimelineSnapshotRowEntity>,
    ): NormalizedTimelineRead {
        validateHead(scope, head, rows.size)?.let { return NormalizedTimelineRead.Invalid(it) }
        validateRows(scope, rows)?.let { return NormalizedTimelineRead.Invalid(it) }
        val events = decodeRows(rows) ?: return NormalizedTimelineRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        val envelope = head.toEnvelope(scope, events)
        return if (normalizedRootDigest(envelope, rows) == head.rootDigest.lowercase()) {
            NormalizedTimelineRead.Valid(envelope)
        } else {
            NormalizedTimelineRead.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
        }
    }

    private fun validateHead(
        scope: TimelineScope,
        head: NormalizedTimelineSnapshotHeadEntity,
        actualRowCount: Int,
    ): SnapshotReadFailure? {
        if (!head.matches(scope)) return SnapshotReadFailure.SCOPE_MISMATCH
        if (head.storageLayoutVersion != NORMALIZED_LAYOUT_VERSION) return SnapshotReadFailure.SCHEMA_MISMATCH
        if (!head.hasValidMetadata()) return SnapshotReadFailure.METADATA_INVALID
        return SnapshotReadFailure.LENGTH_MISMATCH.takeIf { actualRowCount != head.rowCount }
    }

    private fun validateRows(
        scope: TimelineScope,
        rows: List<NormalizedTimelineSnapshotRowEntity>,
    ): SnapshotReadFailure? {
        val identities = HashSet<Pair<Long, Long>>()
        rows.forEachIndexed { index, row ->
            if (row.backendId != scope.backendId || row.conversationId != scope.conversationId) {
                return SnapshotReadFailure.SCOPE_MISMATCH
            }
            if (row.eventOrder != index || !identities.add(row.identityPrimary to row.identitySecondary)) {
                return SnapshotReadFailure.METADATA_INVALID
            }
            if (sha256(row.payload) != row.checksum.lowercase()) {
                return SnapshotReadFailure.CHECKSUM_MISMATCH
            }
        }
        return null
    }

    private fun decodeRows(rows: List<NormalizedTimelineSnapshotRowEntity>): List<StoredTimelineEvent>? =
        rows.map { row ->
            runCatching {
                TimelineSnapshotCodec.json.decodeFromString(
                    StoredTimelineEvent.serializer(),
                    row.payload.toString(StandardCharsets.UTF_8),
                )
            }.getOrNull() ?: return null
        }
}

private fun NormalizedTimelineSnapshotHeadEntity.matches(scope: TimelineScope): Boolean =
    backendId == scope.backendId && conversationId == scope.conversationId && agentId == scope.agentId

private fun NormalizedTimelineSnapshotHeadEntity.hasValidMetadata(): Boolean =
    revision >= 0L && envelopeSchemaVersion in 1..StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION &&
        rowCount >= 0 && generation >= 0L

private fun NormalizedTimelineSnapshotHeadEntity.toEnvelope(
    scope: TimelineScope,
    events: List<StoredTimelineEvent>,
): StoredTimelineEnvelope =
    StoredTimelineEnvelope(
        schemaVersion = envelopeSchemaVersion,
        scope = scope,
        revision = revision,
        liveCursor = liveCursor,
        backfillCursor = backfillCursor,
        releasedOlderCount = releasedOlderCount,
        events = events,
        writtenAtMillis = writtenAtMillis,
    )

internal fun normalizedRootDigest(
    envelope: StoredTimelineEnvelope,
    rows: List<NormalizedTimelineSnapshotRowEntity>,
): String = sha256(buildString {
    appendDigestField(envelope.schemaVersion.toString())
    appendDigestField(envelope.scope.backendId)
    appendNullableDigestField(envelope.scope.agentId)
    appendDigestField(envelope.scope.conversationId)
    appendDigestField(envelope.revision.toString())
    appendNullableDigestField(envelope.liveCursor)
    appendNullableDigestField(envelope.backfillCursor)
    appendDigestField(envelope.releasedOlderCount.toString())
    appendDigestField(envelope.writtenAtMillis.toString())
    rows.forEach { row ->
        appendDigestField(row.identityPrimary.toString())
        appendDigestField(row.identitySecondary.toString())
        appendDigestField(row.eventOrder.toString())
        appendDigestField(row.checksum)
    }
}.toByteArray(StandardCharsets.UTF_8))

private fun StringBuilder.appendNullableDigestField(value: String?) {
    if (value == null) append("N;") else append("S;").appendDigestField(value)
}

private fun StringBuilder.appendDigestField(value: String) {
    append(value.length).append(':').append(value).append(';')
}
