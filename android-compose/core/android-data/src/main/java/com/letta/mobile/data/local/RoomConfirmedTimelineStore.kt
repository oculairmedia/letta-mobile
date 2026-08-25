package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.util.Telemetry
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** CursorWindow-safe Room persistence using metadata heads and bounded BLOB chunks. */
class RoomConfirmedTimelineStore(
    private val database: LettaDatabase,
) : ConfirmedTimelineStore {
    private val dao = database.confirmedTimelineSnapshotDao()
    private val manifestReader = RoomTimelineManifestReader(dao)

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? {
        return readSnapshotResult(scope).snapshot
    }

    override suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult {
        return withContext(Dispatchers.IO) {
        val startedAtMillis = timelineCurrentTimeMillis()
        val head = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            ?: return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
        if (!head.matches(scope)) {
            return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
        }
            readFromHead(RoomSnapshotReadRequest(scope, head, startedAtMillis))
        }
    }

    private suspend fun readFromHead(request: RoomSnapshotReadRequest): ConfirmedTimelineReadResult {
        val activeId = request.head.activeManifestId
            ?: return readWithoutActiveManifest(request)
        return when (val active = manifestReader.read(request.activeManifest(activeId))) {
            is RoomManifestRead.Valid -> activeResult(request, active)
            is RoomManifestRead.Invalid -> fallbackOrReconcile(request, activeId, active.failure)
        }
    }

    private suspend fun readWithoutActiveManifest(request: RoomSnapshotReadRequest): ConfirmedTimelineReadResult {
        val fallback = readFallbackManifest(request, excludedManifestId = null)
        return if (fallback == null) {
            request.reconciliation(SnapshotReadFailure.MISSING)
        } else {
            fallbackResult(request, fallback, SnapshotReadFailure.MISSING)
        }
    }

    private suspend fun fallbackOrReconcile(
        request: RoomSnapshotReadRequest,
        activeManifestId: String,
        activeFailure: SnapshotReadFailure,
    ): ConfirmedTimelineReadResult {
        val fallback = readFallbackManifest(request, excludedManifestId = activeManifestId)
        if (fallback != null) return fallbackResult(request, fallback, activeFailure)
        reportReconciliation(request.scope, activeFailure)
        return request.reconciliation(activeFailure)
    }

    private suspend fun readFallbackManifest(
        request: RoomSnapshotReadRequest,
        excludedManifestId: String?,
    ): RoomManifestRead.Valid? {
        val fallbackId = request.head.fallbackManifestId
            ?.takeUnless { it == excludedManifestId }
            ?: return null
        return manifestReader.read(request.fallbackManifest(fallbackId)) as? RoomManifestRead.Valid
    }

    private fun activeResult(request: RoomSnapshotReadRequest, read: RoomManifestRead.Valid): ConfirmedTimelineReadResult {
        reportRead(RoomReadObservation(request, read, RoomReadSource.ACTIVE))
        return ConfirmedTimelineReadResult.Active(read.envelope, request.head.highWaterRevision)
    }

    private fun fallbackResult(
        request: RoomSnapshotReadRequest,
        read: RoomManifestRead.Valid,
        activeFailure: SnapshotReadFailure,
    ): ConfirmedTimelineReadResult {
        reportRead(RoomReadObservation(request, read, RoomReadSource.FALLBACK))
        return ConfirmedTimelineReadResult.Fallback(
            snapshot = read.envelope,
            activeFailure = activeFailure,
            highWaterRevision = request.head.highWaterRevision,
        )
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean {
        return withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val writtenAt = envelope.writtenAtMillis.takeIf { it > 0 } ?: timelineCurrentTimeMillis()
        val normalized = envelope.copy(writtenAtMillis = writtenAt)
        val payload = TimelineSnapshotCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_PAYLOAD_BYTES) {
            "Snapshot exceeds bounded storage limit"
        }

        val manifestId = UUID.randomUUID().toString()
        val chunks = payload.asTimelineChunks(manifestId)
        val manifest = ConfirmedTimelineSnapshotManifestEntity(
            manifestId = manifestId,
            backendId = scope.backendId,
            conversationId = scope.conversationId,
            agentId = scope.agentId,
            revision = normalized.revision,
            schemaVersion = normalized.schemaVersion,
            byteLength = payload.size.toLong(),
            chunkCount = chunks.size,
            sha256 = sha256(payload),
            writtenAtMillis = writtenAt,
        )
        var published = false

        try {
            // Phase one: commit the complete body without making it visible to readers.
            database.withTransaction {
                dao.insertManifest(manifest)
                chunks.chunked(CHUNK_INSERT_BATCH).forEach { batch ->
                    coroutineContext.ensureActive()
                    dao.insertChunks(batch)
                }
            }

            // Validate exactly through the production bounded read path before publishing the head.
            val staged = manifestReader.read(
                RoomManifestRequest(
                    scope = scope,
                    manifestId = manifestId,
                    maximumRevision = normalized.revision,
                    revisionPolicy = RoomRevisionPolicy.EXACT,
                )
            )
            if (staged !is RoomManifestRead.Valid || staged.envelope != normalized) {
                dao.deleteManifest(manifestId)
                return@withContext false
            }

            val observedHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            val retainedFallback = observedHead?.takeIf { it.matches(scope) }?.let { head ->
                selectLastKnownGoodManifest(scope, head)
            }

            // Phase two: atomically retain the last-known-good body and swap only the metadata head.
            val swapped = database.withTransaction {
                val existing = dao.getHeadMetadata(scope.backendId, scope.conversationId)
                if (existing != null && existing.highWaterRevision >= normalized.revision) {
                    false
                } else {
                    val fallbackId = retainedFallback.takeIf { existing == observedHead }
                    dao.replaceHead(
                        ConfirmedTimelineSnapshotHeadEntity(
                            backendId = scope.backendId,
                            conversationId = scope.conversationId,
                            agentId = scope.agentId,
                            activeManifestId = manifestId,
                            fallbackManifestId = fallbackId,
                            highWaterRevision = normalized.revision,
                            writtenAtMillis = writtenAt,
                        )
                    )
                    true
                }
            }
            if (!swapped) {
                dao.deleteManifest(manifestId)
                reportStaleWrite(scope, envelope.revision)
                return@withContext false
            }
            published = true

            // Delete only payloads not referenced by any active/fallback head, including abandoned stages.
            dao.deleteOrphanManifestsForBackend(scope.backendId)
            Telemetry.event(
                "RoomTimelineStore", "writeSnapshot.success",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to envelope.revision,
                "eventCount" to envelope.events.size,
                "byteSize" to payload.size,
                "chunkCount" to chunks.size,
            )
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val isHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)?.activeManifestId == manifestId
                if (!published && !isHead) dao.deleteManifest(manifestId)
            }
            throw cancelled
        } catch (failure: Throwable) {
            val isHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)?.activeManifestId == manifestId
            if (!published && !isHead) {
                dao.deleteManifest(manifestId)
                throw failure
            }
            Telemetry.error(
                "RoomTimelineStore", "writeSnapshot.postPublishCleanupFailed", failure,
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to envelope.revision,
            )
            true
        }
        }
    }

    override suspend fun deleteSnapshot(scope: TimelineScope) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteHead(scope.backendId, scope.conversationId)
                dao.deleteManifestsForScope(scope.backendId, scope.conversationId)
            }
        }
    }

    override suspend fun clearForBackend(backendId: String) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearHeadsForBackend(backendId)
                dao.clearManifestsForBackend(backendId)
            }
        }
    }

    override suspend fun prune(backendId: String, maxRetainedConversations: Int) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            if (maxRetainedConversations <= 0) {
                dao.clearHeadsForBackend(backendId)
                dao.clearManifestsForBackend(backendId)
            } else {
                dao.pruneHeads(backendId, maxRetainedConversations)
                    dao.deleteOrphanManifestsForBackend(backendId)
                }
            }
        }
    }

    private suspend fun selectLastKnownGoodManifest(
        scope: TimelineScope,
        head: ConfirmedTimelineSnapshotHeadMetadata,
    ): String? {
        val request = RoomSnapshotReadRequest(scope, head, startedAtMillis = 0L)
        val activeId = head.activeManifestId
        if (activeId != null && manifestReader.read(request.activeManifest(activeId)) is RoomManifestRead.Valid) {
            return activeId
        }
        return head.fallbackManifestId
            ?.takeUnless { it == activeId }
            ?.takeIf { manifestReader.read(request.fallbackManifest(it)) is RoomManifestRead.Valid }
    }

    private fun reportRead(observation: RoomReadObservation) {
        Telemetry.event(
            "RoomTimelineStore", observation.source.telemetryEvent,
            "backendId" to observation.request.scope.backendId,
            "conversationId" to observation.request.scope.conversationId,
            "revision" to observation.read.envelope.revision,
            "eventCount" to observation.read.envelope.events.size,
            "byteSize" to observation.read.byteLength,
            "readDurationMs" to (timelineCurrentTimeMillis() - observation.request.startedAtMillis),
        )
    }

    private fun reportReconciliation(scope: TimelineScope, failure: SnapshotReadFailure) {
        Telemetry.event(
            "RoomTimelineStore", "readSnapshot.reconciliationRequired",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "failure" to failure.name,
            level = Telemetry.Level.WARN,
        )
    }

    private fun reportStaleWrite(scope: TimelineScope, revision: Long) {
        Telemetry.event(
            "RoomTimelineStore", "writeSnapshot.staleRejected",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "attemptedRevision" to revision,
            level = Telemetry.Level.WARN,
        )
    }

    companion object {
        const val CHUNK_SIZE_BYTES = 128 * 1024
        private const val CHUNK_INSERT_BATCH = 32
        private const val MAX_CHUNK_COUNT = 2048
        private const val MAX_PAYLOAD_BYTES = CHUNK_SIZE_BYTES.toLong() * MAX_CHUNK_COUNT
    }
}
