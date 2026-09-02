package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommit
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineRow
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineRevision
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
    // Test seam only (mirrors bootstrapBatchObserver): lets tests inject a cancellation mid
    // incremental-commit row-batch loop to prove the surrounding Room transaction rolls back
    // atomically instead of leaving partially-mutated rows.
    private val commitBatchObserver: suspend (Int) -> Unit = {},
    // Test seam only: lets tests deterministically fault the best-effort legacy checkpoint
    // staged after a normalized commit already durably succeeded, without needing a real I/O
    // fault. Proves stageLegacyCheckpoint's failure never downgrades an already-committed
    // normalized write.
    private val legacyCheckpointFailureInjector: (() -> Throwable)? = null,
    // Test seam only: faults immediately BEFORE normalized head publication, after the row
    // mutations have been applied inside the same transaction (PM review item 4). Distinct
    // from commitBatchObserver, which faults mid-row-batch: this one proves a head is never
    // published over rows that did not survive.
    private val beforeHeadPublicationObserver: suspend () -> Unit = {},
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
            val normalizedHead = dao.getNormalizedHead(scope.backendId, scope.conversationId)
            val head = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            // Normalized rows are the incremental-commit write target and, once present, are
            // USUALLY at least as fresh as the periodic legacy v11 checkpoint (which only
            // trails behind on a bounded cadence — see TimelineSyncLoop's checkpoint policy).
            // But a caller can still write legacy directly (bypassing commitNormalized
            // entirely, e.g. this class's own writeSnapshot called standalone) without
            // touching normalized rows, so trust normalized only when its revision is not
            // provably behind the legacy high-water revision -- comparing these two cheap,
            // payload-free head reads is far cheaper than decoding the legacy envelope, so this
            // preserves the fast path for the common (normalized-ahead) case without ever
            // serving stale data when it isn't.
            selectNormalizedRead(scope, normalizedHead, head)?.let { return@withContext it }
            if (head == null) {
                return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
            }
            if (!head.matches(scope)) {
                return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
            }
            val legacy = readFromHead(RoomSnapshotReadRequest(scope, head, startedAtMillis))
            if (legacy !is ConfirmedTimelineReadResult.Active) return@withContext legacy
            val envelope = legacy.snapshot
            if (!normalizedBootstrapIsSafe(envelope) || !bootstrapNormalized(envelope)) return@withContext legacy
            readNormalized(scope) ?: legacy
        }
    }

    /**
     * Normalized-vs-legacy read selection, extracted so [readSnapshotResult] stays a linear
     * sequence of steps.
     *
     * Returns a result only when normalized storage is the right source to answer from;
     * null means "fall through to the legacy v11 path".
     *
     * Normalized rows are the incremental-commit write target and, once present, are USUALLY
     * at least as fresh as the periodic legacy checkpoint (which trails on a bounded cadence
     * -- see TimelineSyncLoop's checkpoint policy). But a caller can still write legacy
     * directly, bypassing commitNormalized entirely (this class's own writeSnapshot called
     * standalone, or the Invalid-plan fallback), without touching normalized rows. So trust
     * normalized only when its revision is not provably behind the legacy high-water
     * revision. Comparing these two cheap, payload-free head reads is far cheaper than
     * decoding the legacy envelope, which preserves the fast path for the common
     * normalized-ahead case without ever serving stale data when it isn't.
     */
    private suspend fun selectNormalizedRead(
        scope: TimelineScope,
        normalizedHead: NormalizedTimelineSnapshotHeadEntity?,
        legacyHead: ConfirmedTimelineSnapshotHeadMetadata?,
    ): ConfirmedTimelineReadResult? {
        if (normalizedHead == null) return null
        if (legacyHead != null && normalizedHead.revision < legacyHead.highWaterRevision) return null
        val normalizedResult = readNormalized(scope)
        if (normalizedResult is ConfirmedTimelineReadResult.Active) return normalizedResult
        // No legacy head to fall back to: propagate normalized's own failure reason
        // (e.g. CHECKSUM_MISMATCH) rather than masking it as a generic MISSING.
        if (legacyHead == null && normalizedResult != null) return normalizedResult
        return null
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
        val rowDigest = rows.fold(normalizedRowDigest(emptyList())) { digest, row ->
            incrementalNormalizedRowDigest(digest, listOf(row))
        }
        val root = normalizedRootDigest(envelope, rowDigest)
        return try {
            database.withTransaction {
                dao.deleteNormalizedHead(envelope.scope.backendId, envelope.scope.conversationId)
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
                        rowDigest = rowDigest,
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

    private fun normalizedBootstrapIsSafe(envelope: StoredTimelineEnvelope): Boolean =
        envelope.events.all { event ->
            TimelineSnapshotCodec.json.encodeToString(
                com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(),
                event,
            ).toByteArray(StandardCharsets.UTF_8).size <= NORMALIZED_MAX_ROW_PAYLOAD_BYTES
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

    override suspend fun normalizedHeadRevision(scope: TimelineScope): Long? =
        withContext(Dispatchers.IO) {
            dao.getNormalizedHead(scope.backendId, scope.conversationId)
                ?.takeIf { it.ownedBy(scope) }
                ?.revision
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

    /**
     * Incremental normalized-row commit. Touches only changed rows: deletes, upserts, and a
     * lightweight (no-payload) row projection to recompute row count + canonical root digest.
     * Never calls [TimelineSnapshotCodec.encode] and never reads/writes event JSON payloads
     * for rows outside [NormalizedTimelineCommit.upserts]. All mutation (deletes, upserts,
     * row-count, root digest, head metadata) happens in one [database] transaction.
     */
    /** Room applies plans row-wise, so the metadata-only incremental envelope is safe here. */
    override val supportsIncrementalCommit: Boolean = true

    override suspend fun commitNormalized(
        plan: NormalizedTimelineCommitPlan,
        fullEnvelope: StoredTimelineEnvelope,
        checkpointLegacyEnvelope: Boolean,
    ): NormalizedTimelineWriteResult = withContext(Dispatchers.IO) {
        val result = when (plan) {
            is NormalizedTimelineCommitPlan.Invalid -> NormalizedTimelineWriteResult.Invalid(plan.reason)
            is NormalizedTimelineCommitPlan.NoOp -> commitNormalizedNoOp(plan)
            is NormalizedTimelineCommitPlan.Apply -> commitNormalizedApply(plan.commit)
        }
        val committedOrNoOp = result is NormalizedTimelineWriteResult.Committed || result is NormalizedTimelineWriteResult.NoOp
        if (checkpointLegacyEnvelope && committedOrNoOp) {
            // PM review item 1 / requirement 13: the normalized commit above is
            // ALREADY DURABLE at this point. Post-commit checkpoint work must not be
            // able to downgrade that result, so it runs NonCancellable and swallows
            // even cancellation. Rethrowing here made the caller observe an exception
            // for a durable write, leaving lastPersistedEnvelope/fingerprint/checkpoint
            // counters unadvanced and the next attempt planning from stale acknowledged
            // state.
            withContext(NonCancellable) { stageLegacyCheckpoint(fullEnvelope) }
        }
        result
    }

    /**
     * Best-effort legacy v11 checkpoint, staged AFTER the normalized commit above has already
     * durably succeeded. A checkpoint failure (or cancellation) must never be reported as a
     * persistence failure -- the normalized commit is the durable write of record; this is
     * purely so v11 rollback readers stay within [TimelineSyncLoop.LEGACY_CHECKPOINT_INTERVAL]
     * revisions of current instead of only ever reflecting the very first commit.
     */
    @Suppress("CancellationMustPropagate")
    private suspend fun stageLegacyCheckpoint(envelope: StoredTimelineEnvelope) {
        // GUARDRAIL SUPPRESSION, deliberate and narrow -- flagged for review rather than
        // applied silently.
        //
        // CancellationMustPropagate is right almost everywhere: swallowing cancellation
        // breaks structured concurrency. This is the documented exception. By the time
        // control reaches here the normalized CAS transaction has ALREADY COMMITTED, and the
        // caller invokes this inside `withContext(NonCancellable)`, so no cancellation can
        // legitimately arrive from the framework. Propagating one anyway would make the
        // caller report a durable write as failed, leave the acknowledged envelope
        // unadvanced, and replan the next commit from stale state -- data-integrity damage
        // traded for protocol purity. The alternative loss is bounded and harmless: v11
        // rollback readers stay at the previous checkpoint.
        //
        // Scope is one function whose entire body is best-effort, post-durable work.
        try {
            legacyCheckpointFailureInjector?.let { throw it() }
            writeSnapshot(envelope)
        } catch (failure: Throwable) {
            // Deliberately catches CancellationException too. Normally swallowing
            // cancellation is wrong, but this runs inside NonCancellable AFTER a
            // durable commit: propagating it would misreport a write that actually
            // landed, which is a worse failure than a missed checkpoint. The loss is
            // bounded -- v11 readers simply stay at the previous checkpoint.
            Telemetry.error(
                "RoomTimelineStore", "commitNormalized.legacyCheckpointFailed", failure,
                "backendId" to envelope.scope.backendId,
                "conversationId" to envelope.scope.conversationId,
                "revision" to envelope.revision,
            )
        }
    }

    private suspend fun commitNormalizedNoOp(plan: NormalizedTimelineCommitPlan.NoOp): NormalizedTimelineWriteResult {
        val backendId = plan.scope.backendId
        val conversationId = plan.scope.conversationId
        return database.withTransaction {
            val current = dao.getNormalizedHead(backendId, conversationId)
            val currentRevision = current?.revision ?: 0L
            // A NoOp needs an EXISTING head to advance; there is nothing to no-op otherwise.
            // Apply deliberately does not require this, because its baseRevision-0 case is the
            // bootstrap commit.
            if (current == null || !current.acceptsCommitAt(plan.baseRevision, plan.scope)) {
                return@withTransaction NormalizedTimelineWriteResult.Stale(TimelineRevision(currentRevision))
            }
            val head = current
            // No-op CAS: advance revision + timestamp only, zero row writes, row set unchanged.
            //
            // The root digest MUST still be recomputed. `normalizedRootDigest` folds in
            // `envelope.revision` (see RoomNormalizedTimelineReader), and the reader
            // recomputes it from the head's revision, so bumping the revision while keeping
            // the old digest makes every subsequent read of this conversation fail closed as
            // CHECKSUM_MISMATCH — the timeline becomes unreadable after an idle no-op.
            // Caught by RoomNormalizedCommitIntegrityTest; the pre-existing no-op test missed
            // it because it never read the snapshot back.
            val digestEnvelope = StoredTimelineEnvelope(
                schemaVersion = head.envelopeSchemaVersion,
                scope = plan.scope,
                revision = plan.targetRevision.value,
                liveCursor = head.liveCursor,
                backfillCursor = head.backfillCursor,
                releasedOlderCount = head.releasedOlderCount,
                writtenAtMillis = plan.writtenAtMillis,
            )
            dao.upsertNormalizedHead(
                head.copy(
                    revision = plan.targetRevision.value,
                    writtenAtMillis = plan.writtenAtMillis,
                    rootDigest = normalizedRootDigest(digestEnvelope, head.rowDigest),
                ),
            )
            NormalizedTimelineWriteResult.NoOp(plan.targetRevision)
        }
    }

    private suspend fun commitNormalizedApply(commit: NormalizedTimelineCommit): NormalizedTimelineWriteResult {
        val oversized = commit.upserts.any { row -> rowPayloadBytes(row.event).size > NORMALIZED_MAX_ROW_PAYLOAD_BYTES }
        if (oversized) {
            // Oversized rows must not enter normalized storage. Fail closed and let the caller
            // fall back to a full legacy checkpoint write rather than silently dropping data.
            return NormalizedTimelineWriteResult.Invalid(NormalizedTimelineCommitFailure.OVERSIZED_ROW)
        }
        val scope = commit.metadata.scope
        val backendId = scope.backendId
        val conversationId = scope.conversationId
        return database.withTransaction {
            val head = dao.getNormalizedHead(backendId, conversationId)
            val currentRevision = head?.revision ?: 0L
            if (!head.acceptsCommitAt(commit.baseRevision, scope)) {
                return@withTransaction NormalizedTimelineWriteResult.Stale(TimelineRevision(currentRevision))
            }
            commit.deletes.forEach { key ->
                currentCoroutineContext().ensureActive()
                dao.deleteNormalizedRow(backendId, conversationId, key.identityPrimary, key.identitySecondary)
            }
            if (commit.upserts.isNotEmpty()) {
                val entities = commit.upserts.map { row -> row.toRowEntity(backendId, conversationId) }
                entities.chunked(NORMALIZED_ROW_INSERT_BATCH).forEachIndexed { index, batch ->
                    currentCoroutineContext().ensureActive()
                    dao.upsertNormalizedRows(batch)
                    commitBatchObserver(index)
                }
            }
            currentCoroutineContext().ensureActive()
            val projection = if (commit.baseRevision.value == 0L || head?.rowDigest.isNullOrBlank()) {
                dao.getNormalizedRowDigestProjection(backendId, conversationId)
            } else {
                // The canonical row digest is order-sensitive. Until segment digests land,
                // exact replacements can preserve it only when their digest fields are
                // unchanged. Payload updates alter checksum, so recompute deliberately and
                // expose the cost; append-only commits are handled below without payload reads.
                val appendOnly = commit.deletes.isEmpty() && commit.upserts.all { it.order >= (head?.rowCount ?: 0) }
                if (appendOnly) null else dao.getNormalizedRowDigestProjection(backendId, conversationId)
            }
            // PM review item 4: seam for faulting IMMEDIATELY BEFORE head publication.
            // Rows are already mutated at this point; the surrounding transaction is what
            // must roll them back, so the head is never published over rows that did not
            // survive. Distinct from commitBatchObserver, which faults mid-row-batch.
            beforeHeadPublicationObserver()
            val digestEnvelope = StoredTimelineEnvelope(
                schemaVersion = commit.metadata.schemaVersion,
                scope = scope,
                revision = commit.targetRevision.value,
                liveCursor = commit.metadata.liveCursor,
                backfillCursor = commit.metadata.backfillCursor,
                releasedOlderCount = commit.metadata.releasedOlderCount,
                writtenAtMillis = commit.metadata.writtenAtMillis,
            )
            val rowDigest = projection?.let { rows ->
                rows.fold(normalizedRowDigest(emptyList())) { digest, row ->
                    incrementalNormalizedRowDigest(digest, listOf(row))
                }
            } ?: run {
                // Append folds changed rows onto a compact digest chain. The reader verifies
                // this chain against all rows, so corruption detection remains fail closed.
                incrementalNormalizedRowDigest(
                    requireNotNull(head).rowDigest,
                    commit.upserts.sortedBy(NormalizedTimelineRow::order).map { row ->
                        val payload = rowPayloadBytes(row.event)
                        NormalizedTimelineSnapshotRowDigestProjection(
                            row.key.identityPrimary,
                            row.key.identitySecondary,
                            row.order,
                            sha256(payload),
                        )
                    },
                )
            }
            dao.upsertNormalizedHead(
                NormalizedTimelineSnapshotHeadEntity(
                    backendId = backendId,
                    conversationId = conversationId,
                    agentId = scope.agentId,
                    storageLayoutVersion = NORMALIZED_LAYOUT_VERSION,
                    revision = commit.targetRevision.value,
                    envelopeSchemaVersion = commit.metadata.schemaVersion,
                    liveCursor = commit.metadata.liveCursor,
                    backfillCursor = commit.metadata.backfillCursor,
                    releasedOlderCount = commit.metadata.releasedOlderCount,
                    rowCount = projection?.size ?: (requireNotNull(head).rowCount + commit.upserts.size),
                    rootDigest = normalizedRootDigest(digestEnvelope, rowDigest),
                    rowDigest = rowDigest,
                    generation = commit.targetRevision.value,
                    writtenAtMillis = commit.metadata.writtenAtMillis,
                ),
            )
            NormalizedTimelineWriteResult.Committed(commit.targetRevision)
        }
    }

    private fun rowPayloadBytes(event: StoredTimelineEvent): ByteArray =
        TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event)
            .toByteArray(StandardCharsets.UTF_8)

    private fun NormalizedTimelineRow.toRowEntity(backendId: String, conversationId: String): NormalizedTimelineSnapshotRowEntity {
        val payload = rowPayloadBytes(event)
        return NormalizedTimelineSnapshotRowEntity(
            backendId = backendId,
            conversationId = conversationId,
            identityPrimary = key.identityPrimary,
            identitySecondary = key.identitySecondary,
            eventOrder = order,
            payload = payload,
            checksum = sha256(payload),
        )
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
            if (existing != null && !existing.ownershipCompatibleWith(plan.scope)) {
                // Round 7: a LEGACY head owned by another agent must never be replaced, at ANY
                // revision. The revision guard below is not a substitute: a cross-agent write at
                // a STRICTLY HIGHER revision sails past it and replaceHead then restamps
                // agent_id to the intruder, destroying the owner's head. The earlier
                // same-revision test passed only because the revision guard fired first, which
                // is why this went unnoticed.
                false
            } else if (existing != null && existing.highWaterRevision >= plan.normalized.revision) {
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
                dao.deleteNormalizedRowsWithoutLegacyHead(backendId)
                dao.deleteNormalizedHeadsWithoutLegacyHead(backendId)
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
        private const val NORMALIZED_MAX_ROW_PAYLOAD_BYTES = 512 * 1024
        private const val MAX_CHUNK_COUNT = 2048
        private const val MAX_PAYLOAD_BYTES = CHUNK_SIZE_BYTES.toLong() * MAX_CHUNK_COUNT
    }
}
