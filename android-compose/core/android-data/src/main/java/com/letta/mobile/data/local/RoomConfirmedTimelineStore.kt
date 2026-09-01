package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** CursorWindow-safe Room persistence using metadata heads and bounded BLOB chunks. */
class RoomConfirmedTimelineStore(
    private val database: LettaDatabase,
    private val bootstrapBatchObserver: suspend (Int) -> Unit = {},
) : ConfirmedTimelineStore {
    private val dao = database.confirmedTimelineSnapshotDao()
    private val manifestReader = RoomTimelineManifestReader(dao)
    private val normalizedReader = RoomNormalizedTimelineReader()

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? {
        return readSnapshotResult(scope).snapshot
    }

    override suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult {
        return withContext(Dispatchers.IO) {
            val startedAtMillis = timelineCurrentTimeMillis()
            val head = dao.getHeadMetadata(scope.backendId, scope.conversationId)
                ?: return@withContext readNormalized(scope)
                    ?: ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
            if (!head.matches(scope)) {
                return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
            }
            val legacy = readFromHead(RoomSnapshotReadRequest(scope, head, startedAtMillis))
            if (legacy !is ConfirmedTimelineReadResult.Active) return@withContext legacy
            val envelope = legacy.snapshot
            if (!bootstrapNormalized(envelope)) return@withContext legacy
            readNormalized(scope) ?: legacy
        }
    }

    private suspend fun readNormalized(scope: TimelineScope): ConfirmedTimelineReadResult? {
        val normalizedHead = dao.getNormalizedHead(scope.backendId, scope.conversationId)
            ?: return null
        return when (val read = normalizedReader.read(scope, normalizedHead, dao.getNormalizedRows(scope.backendId, scope.conversationId))) {
            is NormalizedTimelineRead.Valid -> ConfirmedTimelineReadResult.Active(read.envelope)
            is NormalizedTimelineRead.Invalid -> ConfirmedTimelineReadResult.ReconciliationRequired(read.failure, normalizedHead.revision)
        }
    }

    private suspend fun bootstrapNormalized(envelope: StoredTimelineEnvelope): Boolean {
        val plan = NormalizedTimelineCommitPlanner.plan(null, envelope) as? NormalizedTimelineCommitPlan.Apply ?: return false
        val rows = plan.commit.upserts.map { row ->
            val payload = TimelineSnapshotCodec.json.encodeToString(com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), row.event).toByteArray(StandardCharsets.UTF_8)
            NormalizedTimelineSnapshotRowEntity(
                backendId = envelope.scope.backendId,
                conversationId = envelope.scope.conversationId,
                identityPrimary = row.key.identityPrimary,
                identitySecondary = row.key.identitySecondary,
                eventOrder = row.order,
                payload = payload,
                checksum = sha256(payload),
            )
        }
        val root = normalizedRootDigest(envelope, rows)
        return try {
            database.withTransaction {
                dao.deleteNormalizedRows(envelope.scope.backendId, envelope.scope.conversationId)
                rows.chunked(NORMALIZED_ROW_INSERT_BATCH).forEachIndexed { index, batch ->
                    currentCoroutineContext().ensureActive()
                    dao.insertNormalizedRows(batch)
                    bootstrapBatchObserver(index)
                }
                dao.insertNormalizedHead(
                    NormalizedTimelineSnapshotHeadEntity(
                        backendId = envelope.scope.backendId,
                        conversationId = envelope.scope.conversationId,
                        agentId = envelope.scope.agentId,
                        storageLayoutVersion = NORMALIZED_LAYOUT_VERSION,
                        revision = envelope.revision,
                        envelopeSchemaVersion = envelope.schemaVersion,
                        liveCursor = envelope.liveCursor,
                        backfillCursor = envelope.backfillCursor,
                        releasedOlderCount = envelope.releasedOlderCount,
                        rowCount = rows.size,
                        rootDigest = root,
                        generation = envelope.revision,
                        writtenAtMillis = envelope.writtenAtMillis,
                    )
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
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
        val plan = createWritePlan(envelope)
        var published = false

        try {
            if (!stageAndValidate(plan)) return@withContext false
            if (!publishHead(plan)) {
                dao.deleteManifest(plan.manifestId)
                reportStaleWrite(plan.scope, envelope.revision)
                return@withContext false
            }
            published = true

            // Delete only payloads not referenced by any active/fallback head, including abandoned stages.
            dao.deleteOrphanManifestsForBackend(plan.scope.backendId)
            reportWriteSuccess(plan)
            true
        } catch (cancelled: CancellationException) {
            cleanupCancelledWrite(plan, published)
            throw cancelled
        } catch (failure: Throwable) {
            handleWriteFailure(plan, published, failure)
        }
        }
    }

    private fun createWritePlan(envelope: StoredTimelineEnvelope): SnapshotWritePlan {
        val writtenAt = envelope.writtenAtMillis.takeIf { it > 0 } ?: timelineCurrentTimeMillis()
        val normalized = envelope.copy(writtenAtMillis = writtenAt)
        val payload = TimelineSnapshotCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_PAYLOAD_BYTES) {
            "Snapshot exceeds bounded storage limit"
        }
        val manifestId = UUID.randomUUID().toString()
        val chunks = payload.asTimelineChunks(manifestId)
        return SnapshotWritePlan(
            envelope = envelope,
            normalized = normalized,
            payload = payload,
            chunks = chunks,
            manifest = ConfirmedTimelineSnapshotManifestEntity(
                manifestId = manifestId,
                backendId = envelope.scope.backendId,
                conversationId = envelope.scope.conversationId,
                agentId = envelope.scope.agentId,
                revision = normalized.revision,
                schemaVersion = normalized.schemaVersion,
                byteLength = payload.size.toLong(),
                chunkCount = chunks.size,
                sha256 = sha256(payload),
                writtenAtMillis = writtenAt,
            ),
        )
    }

    private suspend fun stageAndValidate(plan: SnapshotWritePlan): Boolean {
        database.withTransaction {
            dao.insertManifest(plan.manifest)
            plan.chunks.chunked(CHUNK_INSERT_BATCH).forEach { batch ->
                currentCoroutineContext().ensureActive()
                dao.insertChunks(batch)
            }
        }
        val staged = manifestReader.read(
            RoomManifestRequest(
                scope = plan.scope,
                manifestId = plan.manifestId,
                maximumRevision = plan.normalized.revision,
                revisionPolicy = RoomRevisionPolicy.EXACT,
            )
        )
        if (staged is RoomManifestRead.Valid && staged.envelope == plan.normalized) return true
        dao.deleteManifest(plan.manifestId)
        return false
    }

    private suspend fun publishHead(plan: SnapshotWritePlan): Boolean {
        val observedHead = dao.getHeadMetadata(plan.scope.backendId, plan.scope.conversationId)
        val retainedFallback = observedHead?.takeIf { it.matches(plan.scope) }?.let { head ->
            selectLastKnownGoodManifest(plan.scope, head)
        }
        return database.withTransaction {
            val existing = dao.getHeadMetadata(plan.scope.backendId, plan.scope.conversationId)
            if (existing != null && existing.highWaterRevision >= plan.normalized.revision) {
                false
            } else {
                dao.replaceHead(
                    ConfirmedTimelineSnapshotHeadEntity(
                        backendId = plan.scope.backendId,
                        conversationId = plan.scope.conversationId,
                        agentId = plan.scope.agentId,
                        activeManifestId = plan.manifestId,
                        fallbackManifestId = retainedFallback.takeIf { existing == observedHead },
                        highWaterRevision = plan.normalized.revision,
                        writtenAtMillis = plan.normalized.writtenAtMillis,
                    )
                )
                true
            }
        }
    }

    private suspend fun cleanupCancelledWrite(plan: SnapshotWritePlan, published: Boolean) {
        withContext(NonCancellable) {
            val isHead = dao.getHeadMetadata(plan.scope.backendId, plan.scope.conversationId)
                ?.activeManifestId == plan.manifestId
            if (!published && !isHead) dao.deleteManifest(plan.manifestId)
        }
    }

    private suspend fun handleWriteFailure(
        plan: SnapshotWritePlan,
        published: Boolean,
        failure: Throwable,
    ): Boolean {
        val isHead = dao.getHeadMetadata(plan.scope.backendId, plan.scope.conversationId)
            ?.activeManifestId == plan.manifestId
        if (!published && !isHead) {
            dao.deleteManifest(plan.manifestId)
            throw failure
        }
        Telemetry.error(
            "RoomTimelineStore", "writeSnapshot.postPublishCleanupFailed", failure,
            "backendId" to plan.scope.backendId,
            "conversationId" to plan.scope.conversationId,
            "revision" to plan.envelope.revision,
        )
        return true
    }

    private fun reportWriteSuccess(plan: SnapshotWritePlan) {
        Telemetry.event(
            "RoomTimelineStore", "writeSnapshot.success",
            "backendId" to plan.scope.backendId,
            "conversationId" to plan.scope.conversationId,
            "revision" to plan.envelope.revision,
            "eventCount" to plan.envelope.events.size,
            "byteSize" to plan.payload.size,
            "chunkCount" to plan.chunks.size,
        )
    }

    override suspend fun deleteSnapshot(scope: TimelineScope) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteNormalizedHead(scope.backendId, scope.conversationId)
            dao.deleteNormalizedRows(scope.backendId, scope.conversationId)
            dao.deleteHead(scope.backendId, scope.conversationId)
            dao.deleteManifestsForScope(scope.backendId, scope.conversationId)
            }
        }
    }

    override suspend fun clearForBackend(backendId: String) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearNormalizedHeadsForBackend(backendId)
            dao.clearNormalizedRowsForBackend(backendId)
            dao.clearHeadsForBackend(backendId)
            dao.clearManifestsForBackend(backendId)
            }
        }
    }

    override suspend fun prune(backendId: String, maxRetainedConversations: Int) {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            if (maxRetainedConversations <= 0) {
                dao.clearNormalizedHeadsForBackend(backendId)
                dao.clearNormalizedRowsForBackend(backendId)
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

    private class SnapshotWritePlan(
        val envelope: StoredTimelineEnvelope,
        val normalized: StoredTimelineEnvelope,
        val payload: ByteArray,
        val chunks: List<ConfirmedTimelineSnapshotChunkEntity>,
        val manifest: ConfirmedTimelineSnapshotManifestEntity,
    ) {
        val scope: TimelineScope get() = envelope.scope
        val manifestId: String get() = manifest.manifestId
    }

    companion object {
        const val CHUNK_SIZE_BYTES = 128 * 1024
        private const val CHUNK_INSERT_BATCH = 32
        private const val NORMALIZED_ROW_INSERT_BATCH = 256
        private const val MAX_CHUNK_COUNT = 2048
        private const val MAX_PAYLOAD_BYTES = CHUNK_SIZE_BYTES.toLong() * MAX_CHUNK_COUNT
    }
}
