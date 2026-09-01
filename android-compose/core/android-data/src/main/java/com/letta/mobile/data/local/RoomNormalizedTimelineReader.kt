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
        if (head.backendId != scope.backendId || head.conversationId != scope.conversationId ||
            head.agentId != scope.agentId
        ) return NormalizedTimelineRead.Invalid(SnapshotReadFailure.SCOPE_MISMATCH)
        if (head.storageLayoutVersion != NORMALIZED_LAYOUT_VERSION) {
            return NormalizedTimelineRead.Invalid(SnapshotReadFailure.SCHEMA_MISMATCH)
        }
        if (head.revision < 0L || head.envelopeSchemaVersion !in 1..StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION ||
            head.rowCount < 0 || head.generation < 0L
        ) return NormalizedTimelineRead.Invalid(SnapshotReadFailure.METADATA_INVALID)
        if (rows.size != head.rowCount) return NormalizedTimelineRead.Invalid(SnapshotReadFailure.LENGTH_MISMATCH)
        val identities = HashSet<Pair<Long, Long>>()
        rows.forEachIndexed { index, row ->
            if (row.backendId != scope.backendId || row.conversationId != scope.conversationId) {
                return NormalizedTimelineRead.Invalid(SnapshotReadFailure.SCOPE_MISMATCH)
            }
            if (row.eventOrder != index || !identities.add(row.identityPrimary to row.identitySecondary)) {
                return NormalizedTimelineRead.Invalid(SnapshotReadFailure.METADATA_INVALID)
            }
            if (sha256(row.payload) != row.checksum.lowercase()) {
                return NormalizedTimelineRead.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
            }
        }
        val events = rows.map { row ->
            val event = runCatching {
                TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), row.payload.toString(StandardCharsets.UTF_8))
            }.getOrNull() ?: return NormalizedTimelineRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
            event
        }
        val envelope = StoredTimelineEnvelope(
            schemaVersion = head.envelopeSchemaVersion,
            scope = scope,
            revision = head.revision,
            liveCursor = head.liveCursor,
            backfillCursor = head.backfillCursor,
            releasedOlderCount = head.releasedOlderCount,
            events = events,
            writtenAtMillis = head.writtenAtMillis,
        )
        if (normalizedRootDigest(envelope, rows) != head.rootDigest.lowercase()) {
            return NormalizedTimelineRead.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
        }
        return NormalizedTimelineRead.Valid(envelope)
    }
}

internal fun normalizedRootDigest(
    envelope: StoredTimelineEnvelope,
    rows: List<NormalizedTimelineSnapshotRowEntity>,
): String = sha256(buildString {
    append(envelope.schemaVersion).append('|')
    append(envelope.scope.backendId).append('|').append(envelope.scope.agentId).append('|').append(envelope.scope.conversationId).append('|')
    append(envelope.revision).append('|').append(envelope.liveCursor).append('|').append(envelope.backfillCursor).append('|')
    append(envelope.releasedOlderCount).append('|').append(envelope.writtenAtMillis)
    rows.forEach { row -> append('|').append(row.identityPrimary).append(':').append(row.identitySecondary).append(':').append(row.eventOrder).append(':').append(row.checksum) }
}.toByteArray(StandardCharsets.UTF_8))
