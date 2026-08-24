package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.util.Telemetry

/**
 * Room-backed implementation of [ConfirmedTimelineStore] for Android.
 *
 * Guarantees atomic, revision-safe persistence of confirmed timeline snapshots
 * scoped to specific backends and conversations, preventing cross-session leaks.
 */
class RoomConfirmedTimelineStore(
    private val database: LettaDatabase,
) : ConfirmedTimelineStore {
    private val dao: ConfirmedTimelineSnapshotDao = database.confirmedTimelineSnapshotDao()

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? {
        val start = timelineCurrentTimeMillis()
        val entity = dao.getSnapshot(
            backendId = scope.backendId,
            conversationId = scope.conversationId,
        ) ?: return null

        val payload = entity.payloadJson
        val decoded = TimelineSnapshotCodec.decode(payload)
        val durationMs = timelineCurrentTimeMillis() - start

        if (decoded != null) {
            val ageMs = (timelineCurrentTimeMillis() - entity.writtenAtMillis).coerceAtLeast(0)
            Telemetry.event(
                "RoomTimelineStore", "readSnapshot.success",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to entity.revision,
                "eventCount" to decoded.events.size,
                "byteSize" to payload.length,
                "readDurationMs" to durationMs,
                "ageMs" to ageMs,
            )
        } else {
            Telemetry.event(
                "RoomTimelineStore", "readSnapshot.corruptRecovered",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to entity.revision,
                level = Telemetry.Level.WARN,
            )
        }

        return decoded
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
        val scope = envelope.scope
        return database.withTransaction {
            val existing = dao.getSnapshot(
                backendId = scope.backendId,
                conversationId = scope.conversationId,
            )

            if (existing != null && existing.revision >= envelope.revision) {
                Telemetry.event(
                    "RoomTimelineStore", "writeSnapshot.staleRejected",
                    "backendId" to scope.backendId,
                    "conversationId" to scope.conversationId,
                    "existingRevision" to existing.revision,
                    "attemptedRevision" to envelope.revision,
                    level = Telemetry.Level.WARN,
                )
                return@withTransaction false
            }

            val payloadJson = TimelineSnapshotCodec.encode(envelope)
            val writtenAt = if (envelope.writtenAtMillis > 0) envelope.writtenAtMillis else timelineCurrentTimeMillis()

            val entity = ConfirmedTimelineSnapshotEntity(
                backendId = scope.backendId,
                conversationId = scope.conversationId,
                agentId = scope.agentId,
                revision = envelope.revision,
                schemaVersion = envelope.schemaVersion,
                payloadJson = payloadJson,
                writtenAtMillis = writtenAt,
            )

            dao.insertOrReplace(entity)

            Telemetry.event(
                "RoomTimelineStore", "writeSnapshot.success",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to envelope.revision,
                "eventCount" to envelope.events.size,
                "byteSize" to payloadJson.length,
            )
            true
        }
    }

    override suspend fun deleteSnapshot(scope: TimelineScope) {
        dao.deleteSnapshot(
            backendId = scope.backendId,
            conversationId = scope.conversationId,
        )
        Telemetry.event(
            "RoomTimelineStore", "deleteSnapshot",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
        )
    }

    override suspend fun clearForBackend(backendId: String) {
        dao.clearForBackend(backendId)
        Telemetry.event(
            "RoomTimelineStore", "clearForBackend",
            "backendId" to backendId,
        )
    }

    override suspend fun prune(backendId: String, maxRetainedConversations: Int) {
        if (maxRetainedConversations <= 0) return
        dao.pruneBackend(backendId, maxRetainedConversations)
        Telemetry.event(
            "RoomTimelineStore", "pruneBackend",
            "backendId" to backendId,
            "maxRetained" to maxRetainedConversations,
        )
    }
}
